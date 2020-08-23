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

(defn ^:private fetch
  ([url] (fetch url nil))
  ([url last-modified]
   (let [builder (java.net.http.HttpRequest/newBuilder (new java.net.URI url))
         request (.build (if last-modified
                           (.header builder "If-Modified-Since" last-modified)
                           builder))
         client (java.net.http.HttpClient/newHttpClient)
         ;; all the exceptions send throws should never happen - fail-fast if they do
         response (.send client request (java.net.http.HttpResponse$BodyHandlers/ofString))]
     (case (.statusCode response)
       200 [::fetched
            (-> (.body response)
                (json/read-str)
                (assoc ::last-modified (get-header response "Last-Modified")))]
       304 [::unmodified nil]
       404 [::not-found nil]
       (do (log/error "Did not understand response code" (.statusCode response))
           [::unsupported-status nil])))))

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
       ;; collect into one channel with the results of all the others
       (async/map list)
       ;; block our thread waiting for the channel. This had better not be in a coroutine!
       (async/<!!)))

(defn ^:private eliminate-symbol-keys
  [m]
  (into {} (filter (fn [[k v]] (not (keyword? k))) m)))

(defn ^:private trim-for-export
  [coll]
  (walk/prewalk #(if (map? %) (eliminate-symbol-keys %) %) coll))

(defn ^:private export-file
  [{{dir "dir"} :conf} {thread-id ::id}]
  (let [fname (str thread-id ".json")]
    (io/file dir fname)))

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

(defn ^:private exit-with-network-error
  [message]
  (log/error message)
  (System/exit 1))

(defn sec->ms [sec] (* 1000 sec))

(defn ^:private wait-time
  [context th]
  (let [base-wait-sec (conf-get (:conf context) "thread" "min-sec-between-refresh")
        ;; if missing (eg. after first fetch) consider it 0
        unmodifieds (get th ::sequential-unmodified-fetches 0)
        ;; seconds = base-wait-sec * (2 ^ unmodifieds)
        ;;
        ;; When unmodifids is 0 this equals base-wait-sec.
        wait-sec (* base-wait-sec (Math/pow 2 unmodifieds))]
    (log/debug "Thread" (thread-url th) "waiting for" wait-sec)
    (sec->ms wait-sec)))

(defn init-thread
  [context board-name thread-id]
  (let [url (thread-url board-name thread-id)
        _ (log/info "Initializing thread" url)
        [fetch-kw fetched-th] (fetch url)]
    (case fetch-kw
      ::fetched (let [th (-> fetched-th
                             (assoc ::id thread-id
                                    ::board board-name
                                    ::conf (:conf context)
                                    ::state (:state context)))
                      posts (process-posts context th (th "posts"))]
                  (log/debug "init th" (dissoc th "posts"))
                  (assoc th "posts" posts))
      ::unsupported-status (exit-with-network-error "Exiting due to unsupported status when"
                                                    "initializing.")
      ::unmodified (exit-with-network-error "Nonsensical cache response: got 304 unmodified when we"
                                            "didn't send If-Modified-Since")
      ::not-found (exit-with-network-error "Got 404 not found when first starting a thread!"))))

(defn update-posts
  "Uses side effects to update the passed thread, applying post transforms, and returns the new
  version"
  [context {old-posts "posts" :as old-thread}]
  (let [[fetch-kw fetched-th] (fetch (thread-url old-thread) (old-thread ::last-modified))]
    (case fetch-kw
      ::fetched (let [raw-new-posts (new-posts-from-json old-posts fetched-th)]
                  (if (empty? raw-new-posts)
                    ;; thread unchanged
                    old-thread
                    ;; process new posts
                    (assoc old-thread
                           "posts" (->> raw-new-posts
                                        (process-posts context old-thread)
                                        (conj old-posts))
                           ::sequential-unmodified-fetches 0)))
      ::unsupported-status old-thread ; unsupported HTTP status, might as well treat as unchanged
      ;; unchanged since last one
      ::unmodified (do (log/info "Thread unchanged since" (old-thread ::last-modified))
                       (update old-thread ::sequential-unmodified-fetches (fnil inc 0)))
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
