(ns channeler.chan-th
  (:require [channeler.transcade :as transcade]
            [channeler.config :refer [conf-get]]
            [clojure.data.json :as json]
            [clojure.walk :as walk]
            [clojure.core.async :as async]
            [clojure.tools.logging :as log]
            [clojure.java.io :as io]
            ))

(defn ^:private new-posts
  [old-posts new-posts]
  ;; TODO: this is very hacky
  (drop (count old-posts) new-posts))

(defn ^:private new-posts-from-json
  [old-posts new-thread-json]
  (new-posts old-posts (new-thread-json "posts")))

(defn ^:private get-header
  [response header-name]
  (let [val-opt (-> response
                    (.headers)
                    (.firstValue header-name))]
    (if (.isPresent val-opt)
      (.get val-opt)
      nil)))

(defn get-and-handle-except
  "Attempt a GET and catch any exception. Returns [response nil] on success and [nil exception] on
  error."
  [url last-modified]
  (try
    (let [builder (-> (new java.net.URI url) ; throws UriSyntaxException
                      (java.net.http.HttpRequest/newBuilder))
                        ;;(except java.net.UriSyntaxException e (log/error "Unusable URL:" e)))]
          final-builder (if last-modified
                          ;; thows IllegalArgumentException
                          (.header builder "If-Modified-Since" last-modified)
                          builder)
          request (.build final-builder) ;; throws IllegalStateException
          client (java.net.http.HttpClient/newHttpClient)]
      ;; throws IoException, InterruptedException, IllegalArgumentException, or SecurityException
      [(.send client request (java.net.http.HttpResponse$BodyHandlers/ofString)) nil])
    (catch Exception e [nil e])))

(defn ^:private fetch
  ([url] (fetch url nil))
  ([url last-modified]
   (let [[response exception] (get-and-handle-except url last-modified)]
     (if response
       (case (.statusCode response)
         200 [::fetched
              (-> (.body response)
                  (json/read-str)
                  (assoc ::last-modified (get-header response "Last-Modified")))]
         304 [::unmodified nil]
         404 [::not-found nil]
         [::unsupported-status (.statusCode response)])
       [::could-not-fetch exception]))))

(defn ^:private post-routine
  [post-transcade th post]
  (transcade/coroutine-loop post-transcade th post))

(defn ^:private process-posts
  "Take posts in json format and process them into our format, by using
  the post-transcade"
  [{{post-transcade :post-transcade} :state} th json-posts]
  (->> json-posts
       ;; channel for each post (fed by coroutine)
       (map (partial post-routine post-transcade th))
       ;; get only one thing
       (map (partial async/take 1))
       ;; collect into one channel with the results of all the others. Must be vector so that conj
       ;; used in update will append.
       (async/map vector)
       ;; block our thread waiting for the channel. This had better not be in a coroutine!
       (async/<!!)))

(defn ^:private eliminate-symbol-keys
  [m]
  (into {} (filter (fn [[k v]] (not (keyword? k))) m)))

(defn ^:private trim-for-export
  [coll]
  (walk/prewalk #(if (map? %) (eliminate-symbol-keys %) %) coll))

(defn ^:private export-file
  [context {thread-id ::id}]
  (let [fname (str thread-id ".json")]
    (io/file (conf-get (:conf context) "dir") fname)))

(def ^:private write-options
  (list :escape-unicode false :escape-js-separators false
        :escape-slash false))

(defn export-thread
  [context th]
  (with-open [w (clojure.java.io/writer (export-file context th))]
    (apply json/write (trim-for-export th) w write-options)))

(defn thread-url
  ([{id ::id board ::board}] (thread-url board id))
  ([board id] (format "https://a.4cdn.org/%s/thread/%s.json" board id)))

(defn sec->ms [sec] (* 1000 sec))

(defn ^:private exponential-backoff-wait-time
  [conf th base-wait-sec]
  (let [;; if missing (eg. after first fetch) consider it 0
        unmodifieds (get th ::sequential-unmodified-fetches 0)
        ;; seconds = base-wait-sec * (2 ^ unmodifieds)
        ;;
        ;; When unmodifids is 0 this equals base-wait-sec.
        computed-sec (* base-wait-sec (Math/pow 2 unmodifieds))
        max-sec (conf-get conf
                          "thread" "no-new-posts-refresh-backoff" "max-sec-between-refresh")]
    (min computed-sec max-sec)))

(defn ^:private wait-time
  [context th]
  (let [conf (:conf context)
        base-wait-sec (conf-get conf "thread" "min-sec-between-refresh")
        strategy (conf-get conf "thread" "no-new-posts-refresh-backoff" "backoff-strategy")
        sec (if (= "exponential" strategy)
              (exponential-backoff-wait-time conf th base-wait-sec)
              base-wait-sec)]
    (log/debug "Thread" (thread-url th) "waiting for" sec)
    (sec->ms sec)))

(defn ^:private mark-unmodified
  "Increment number of times thread was unmodified. If there is no count (effectively 0), this sets it
  to 1."
  [th]
  (update th ::sequential-unmodified-fetches (fnil inc 0)))

(defn init-thread
  [context board-name thread-id]
  (let [url (thread-url board-name thread-id)
        _ (log/info "Initializing thread" url)
        [fetch-kw data] (fetch url)]
    (case fetch-kw
      ::fetched (let [th (-> data
                             (assoc ::id thread-id
                                    ::board board-name
                                    ::conf (:conf context)
                                    ::state (:state context)))
                      posts (process-posts context th (th "posts"))]
                  (log/debug "init th" (dissoc th "posts"))
                  (assoc th "posts" posts))
      ::unsupported-status (log/error "Could not init" url "due to unsupported status" data)
      ::could-not-fetch (log/error data "Could not fetch" url "due to network error")
      ::unmodified (log/error "Could not init" url "due to nonsensical cache response: got 304"
                              "Unmodified when we didn't send If-Modified-Since")
      ::not-found (log/error "Could not init" url "because got 404 Not Found"))))

(defn update-posts
  "Uses side effects to update the passed thread, applying post transforms, and returns the new
  version"
  [context {old-posts "posts" :as old-thread}]
  (let [url (thread-url old-thread)
        [fetch-kw data] (fetch url (old-thread ::last-modified))]
    (case fetch-kw
      ::fetched (let [raw-new-posts (new-posts-from-json old-posts data)]
                  (if (empty? raw-new-posts)
                    ;; thread unchanged
                    (mark-unmodified old-thread)
                    ;; process new posts
                    (assoc old-thread
                           "posts" (->> raw-new-posts
                                        (process-posts context old-thread)
                                        ;; conj in each new post. Appends as long as old posts is vec
                                        (into old-posts))
                           ::sequential-unmodified-fetches 0)))
      ::unsupported-status (do (log/error "Unsupported status" data "when refreshing" url
                                          "- treating as unchanged")
                               old-thread)
      ::could-not-fetch (do (log/error data "Network error refreshing" url "- treating as unchanged")
                            old-thread)
      ;; unchanged since last one
      ::unmodified (do (log/info "Thread unchanged since" (old-thread ::last-modified))
                       (mark-unmodified old-thread))
      ::not-found nil))) ; thread has 404d


(defn thread-loop
  "Refresh thread, infinitely, inline. Sleeps thread to wait."
  [context th]
  (export-thread context th)
  (Thread/sleep (wait-time context th))
  (log/info "Updating" (thread-url th)) ; for now, just use URL to represent thread
  (if-let [new-th (update-posts context th)]
    (recur context new-th)
    (log/info "Thread is 404" (thread-url th))))
