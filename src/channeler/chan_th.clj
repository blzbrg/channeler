(ns channeler.chan-th
  (:require [channeler.transcade :as transcade]
            [channeler.request :as request]
            [channeler.service :as service]
            [channeler.config :refer [conf-get]]
            [channeler.map-tools :as map-tools]
            [channeler.timer :as timer]
            [clojure.data.json :as json]
            [clojure.walk :as walk]
            [clojure.core.async :as async]
            [clojure.tools.logging :as log]
            [clojure.java.io :as io]
            ))

(defn ^:private get-header
  [^java.net.http.HttpResponse response header-name]
  (let [val-opt (-> response
                    (.headers)
                    (.firstValue header-name))]
    (if (.isPresent val-opt)
      (.get val-opt)
      nil)))

(defn verify-dir-usable
  "Return `false` if file exists, is dir, and is writeable, otherwise return an error message."
  [^java.io.File dir-file]
  (let [dir-path (delay (.getAbsolutePath dir-file))]
    (cond
      (not (.exists dir-file)) (str @dir-path " does not exist")
      (not (.isDirectory dir-file)) (str @dir-path " is not a directory")
      (not (.canWrite dir-file)) (str @dir-path " is not writeable")
      :else false)))

(defn first-extant-file
  "Given a file, return the first item that exists, starting at that file and moving up the directory
  tree, or `nil` if none exist."
  [^java.io.File input-file]
  (loop [file input-file]
    (if (.exists file)
      ;; Base case: this file exists
      file
      (if-let [parent (.getParentFile file)]
        ;; Recursive case: examine parent
        (recur parent)
        ;; Base case: no parents exist
        nil))))

(defn verify-one-usable-parent
  "Return `false` if one of the parents of `input-file` is usable according to `verify-dir-usable`,
  otherwiswe return an error message."
  [^java.io.File input-file]
  (if-let [first-extant (first-extant-file input-file)]
    ;; The first parent that exists needs to be usable (so we can create all the dirs in between)
    (verify-dir-usable first-extant)
    ;; If no parent exists, verification fails
    ;;
    ;; TODO: can this be tested?
    (str "no parent of " (.getAbsolutePath input-file) "exists")))

(defn verify-potential-thread
  "Verify a thread before creating it. Returns [true nil] on successful verification or [nil
  error-message] on failure."
  [context [_ _]]
  ;; Verify the filesystem destination
  (let [dir (io/file (conf-get (:conf context) "dir"))
        maybe-err (if-let [create-missing-dirs (conf-get (:conf context) "create-missing-parents")]
                    (verify-one-usable-parent dir)
                    (verify-dir-usable dir))]
    (if maybe-err
      [nil maybe-err]
      [true nil])))

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
                          "refresh" "no-new-posts-refresh-backoff" "max-sec-between-refresh")]
    (min computed-sec max-sec)))

(defn ^:private wait-time
  [conf th]
  (let [base-wait-sec (conf-get conf "refresh" "min-sec-between-refresh")
        strategy (conf-get conf "refresh" "no-new-posts-refresh-backoff" "backoff-strategy")
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

(defn completed?
  "Return truthy if no more refreshes for thread are possible (eg. thread is deleted)"
  [th]
  (::completed th))

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
                ;; Post IDs which are in the new thread but not the old
                new-post-ids (clojure.set/difference (set (keys (get new-thread "posts")))
                                                     (set (keys (get old-thread "posts"))))]
            ;; TODO: this way of updating the thread is complicated, fragile, and hard to understand
            ;;
            ;; TODO: support updating existing posts. For example, the first post contains some
            ;; thread stats, which we never update.
            (let [new-posts (if (not-empty new-post-ids)
                              (transcade-new-posts old-thread post-transcade
                                                   (select-keys (get new-thread "posts")
                                                                new-post-ids))
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

(defn th-error-handler
  [agt ex]
  (log/error "Agent error: " ex)
  (log/debug "Failed th was:"  (pr-str @agt)))

(defn maybe-create-dirs
  "If configured to do so, create any dirs and parents of the thread's output dir."
  [th]
  (if (conf-get (::conf th) "create-missing-parents")
    (let [dir (conf-get (::conf th) "dir")]
      (if (.mkdirs (io/as-file dir))
        (log/debug "Created all parents for" dir)
        (log/error "Failed to create all parents for" dir)))))

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
    (set-error-handler! agt error-handler)
    (add-watch agt ::request-watch (partial request-watch ctx))
    (add-watch agt ::export-watch (partial export-watch ctx))
    (maybe-create-dirs @agt)
    (send agt merge {::self-integration-fn integ-fn
                     :channeler.request/requests {::initial-update initial-update-req}})
    (log/info "Created thread" board no)
    (log/debug "Created thread" (pr-str @agt))
    agt))
