(ns channeler.chan-th
  (:require [channeler.transcade :as transcade]
            [channeler.request :as request]
            [channeler.service :as service]
            [channeler.config :refer [conf-get]]
            [channeler.map-tools :as map-tools]
            [channeler.timer :as timer]
            [clojure.data.json :as json]
            [clojure.walk :as walk]
            [clojure.data :as data]
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

(def default-fetch-timeout
  "Unit: seconds"
  5)

(defn get-and-handle-except
  "Attempt a GET and catch any exception. Returns [response nil] on success and [nil exception] on
  error."
  [url last-modified]
  (try
    (let [builder (-> (new java.net.URI url) ; throws UriSyntaxException
                      (java.net.http.HttpRequest/newBuilder)
                      ;; if exceeded, send will throw HttpTimeoutException
                      (.timeout (java.time.Duration/ofSeconds default-fetch-timeout)))
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
       ;; collect into one channel with the results of all the others. Must be vector so that conj
       ;; used in update will append.
       ;;
       ;; The post-routine channel will only contain one item each, so the channel created by map
       ;; will only contain one item.
       (async/map vector)
       ;; block our thread waiting for the channel. This had better not be in a coroutine!
       (async/<!!)))

(defn ^:private eliminate-symbol-keys
  [m]
  (into {} (filter (fn [[k v]] (not (keyword? k))) m)))

(defn trim-for-export
  [coll]
  (walk/prewalk #(if (map? %) (eliminate-symbol-keys %) %) coll))

(defn ^:private export-file
  [context {thread-id ::id}]
  (let [fname (str thread-id ".json")]
    (io/file (conf-get (:conf context) "dir") fname)))

(def write-options
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
  [conf th]
  (let [base-wait-sec (conf-get conf "thread" "min-sec-between-refresh")
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

(defn ^:private verify-fetch
  [url fetch-kw data]
  (case fetch-kw
    ::unsupported-status (log/error "Could not init" url "due to unsupported status" data)
    ::could-not-fetch (log/error data "Could not fetch" url "due to network error")
    ::unmodified (log/error "Could not init" url "due to nonsensical cache response: got 304"
                   "Unmodified when we didn't send If-Modified-Since")
    ::not-found (log/error "Could not init" url "because got 404 Not Found")
    ::fetched true))

(defn ^:private verify-dir-usable
  [url context]
  (let [dir-file (io/file (conf-get (:conf context) "dir"))]
    (if (.isDirectory dir-file)
      true
      (let [path (.getPath dir-file)]
        (if (.exists dir-file)
          (log/error "Could not init" url "because" path "is a file instead of a directory")
          (log/error "Could not init" url "because" path "does not exist"))))))

(defn completed?
  "Return truthy if no more refreshes for thread are possible (eg. thread is deleted)"
  [th]
  (::completed th))

(defn init
  "Initialize thread. Download the thread, log any issues thereof, and set up state, but do not do any
  post processing."
  [context board-name thread-id]
  (let [url (thread-url board-name thread-id)]
    ;; first, do verifications which don't require fetch
    (if (verify-dir-usable url context)
      ;; then, do the fetch and verify the results
      (let [[fetch-kw data] (fetch url)]
        (if (verify-fetch url fetch-kw data)
          ;; if the fetch succeeded, handle contents
          (let [th (-> data
                       (assoc ::id thread-id
                              ::board board-name
                              ::conf (:conf context)
                              ::state (:state context)))]
            (log/info "Initializing thread" url)
            (log/debug "init th" (dissoc th "posts"))
            th)))))) ; return thread at end

(defn process-current-posts
  "Process all posts currently in the thread. Meant to be called after init to get ready for
  thread-loop."
  [context {posts "posts" :as th}]
  (assoc th "posts" (process-posts context th posts)))

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
      ::not-found (assoc old-thread ::completed 404)))) ; thread has 404d

(defn loop-iteration
  "One cycle around the thread loop: refresh thread and sleep to wait. Return new thread value, or nil
  if thread is 404."
  [context th]
  (export-thread context th)
  (Thread/sleep (wait-time (:conf th) th))
  (log/info "Updating" (thread-url th)) ; for now, just use URL to represent thread
  (let [new-th (update-posts context th)]
    ;; log 404 if it is completed
    (if (completed? new-th)
      (log/info "Thread is 404" (thread-url th)))
    ;; return new thread at end
    new-th))

(defn post-seq->map
  "Convert a sequence of posts into a sorted-map mapping post number to post object."
  [post-seq]
  (->> post-seq
       (map (fn [post] [(post "no") post]))
       (into (sorted-map))))

(defn mapify-thread
  [thread]
  (update thread "posts" post-seq->map))

(defn ^:private transcadeable?
  "Either unsorted or is sorted with keyword keys (sorted cannot mix key types)"
  [d]
  ;; TODO: clean this up
  (or (not (sorted? d))
      (keyword? (first (keys d)))))

(defn find-transcades
  [d]
  (map-tools/find-matching-paths d #(and (transcadeable? %)
                                         (transcade/needed? %))))

(defn response->thread-structure
  [^java.net.http.HttpResponse resp]
  (-> (.body resp)
      (json/read-str)
      (mapify-thread)))

(defn request-watch
  [{{service-map :service-map} :state} _ agt old new]
  (if (not (= old new))
    (send agt (fn [d] (let [reqs (request/contexify-all-reqs d)]
                        (service/dispatch-requests-from-context! service-map d reqs))))))

(defn export-watch
  [ctx _ _ old new]
  (if (not (= old new))
    (export-thread ctx new)))

(defn update-req
  [th]
  (let [wait-nanos (-> (wait-time (::conf th) th)
                       (java.time.Duration/ofMillis)
                       (.toNanos))
        req {:channeler.service/service-key
             :channeler.limited-downloader/rate-limited-downloader
             :channeler.limited-downloader/download-url
             (thread-url th)
             :channeler.limited-downloader/time-nano
             (+ (timer/nano-mono-time-source) wait-nanos)
             :channeler.request/response-dest
             (partial (::self-integration-fn th) (::state th))}]
    (if-let [last-modified (get th ::last-modified)]
      (assoc-in req [:channeler.limited-downloader/headers "If-Modified-Since"] last-modified)
      req)))

(defn ^:private transcade-new-posts
  [old-thread post-transcade new-posts]
  (->> new-posts
       (map second) ; only posts themselves (ignore keys)
       (map (partial transcade/inline-loop post-transcade old-thread))
       (map (fn [post] [(get post "no") post])) ; make k-v tuples
       (into (sorted-map))))

(defn integrate
  [old-thread state incoming-response]
  (log/info "Processing" (::board old-thread) (::id old-thread))
  (let [;; Extract HttpResponse
        ^java.net.http.HttpResponse resp
        (get incoming-response :channeler.limited-downloader/http-response)
        ;; Update `old-thread` based on resp
        th
        (case (.statusCode resp)
          ;; OK
          200
          (let [{post-transcade :post-transcade} state
                new-thread (response->thread-structure resp)
                ;; TODO: apply thread-wide transcade
                [old-only new-only both] (data/diff old-thread new-thread)]
            ;; TODO: this way of updating the thread is complicated, fragile, and hard to understand
            (let [new-posts (if (contains? new-only "posts")
                              (transcade-new-posts old-thread post-transcade (get new-only "posts"))
                              (sorted-map))]
              (-> old-thread
                  (assoc ::last-modified (get-header resp "Last-Modified"))
                  (update "posts" merge new-posts))))
          ;; Not-Found
          404
          (do (log/info "Thread" (::board old-thread) (::id old-thread) "is 404")
              (assoc old-thread ::completed 404))
          ;; Unmodified
          304
          (do (log/info "Thread" (::board old-thread) (::id old-thread) "unchanged since"
                        (::last-modified old-thread))
              (mark-unmodified old-thread))
          ;; Unrecognized status
          (log/error "Unrecognized status code" (.statusCode resp) "Ignoring this refresh"))]
    ;; If thread is not done, queue th+e next refresh.
    (if (not (completed? th))
      (assoc-in th [:channeler.request/requests ::refresh] (update-req th))
      th)))

(defn dispatch-thread-integrate
  [thread-agent state response]
  (send thread-agent integrate state response))

(defn create
  [ctx [board no]]
  (let [agt (agent {::id no ::board board ::conf (:conf ctx) ::state (:state ctx)})
        integ-fn (partial dispatch-thread-integrate agt)
        initial-update-req {:channeler.service/service-key
                            :channeler.limited-downloader/rate-limited-downloader
                            :channeler.limited-downloader/download-url
                            (thread-url @agt)
                            :channeler.request/response-dest
                            (partial integ-fn (::state @agt))}]
    (set-error-handler! agt (fn [_ ex] (println "") (println "Agent error: " ex)))
    (add-watch agt ::request-watch (partial request-watch ctx))
    (add-watch agt ::export-watch (partial export-watch ctx))
    (send agt merge {::self-integration-fn integ-fn
                     :channeler.request/requests {::initial-update initial-update-req}})
    (log/info "Created thread" board no)
    (log/debug "Created thread" (pr-str @agt))
    agt))
