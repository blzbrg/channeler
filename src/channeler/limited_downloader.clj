(ns channeler.limited-downloader
  "Rate limited downloader."
  (:require [channeler.timer :as timer]
            [channeler.service :as service]
            [channeler.request :as request]
            [clojure.java.io :as io]
            [clojure.tools.logging :as log]))

(def ms-between-download 1000)

;; === Work with HTTP ===

(def default-fetch-timeout
  "Unit: seconds"
  5)

(defn add-headers
  ^java.net.http.HttpRequest$Builder [^java.net.http.HttpRequest$Builder builder headers]
  (if (empty? headers)
    ;; Base case: return builder
    builder
    ;; Recursive case: add one header
    (let [[name val] (first headers)]
      (recur (.header builder name val) (rest headers)))))

(defn request
  ([url handler] (request url handler {}))
  ([url handler headers]
   (try
     (let [request (-> (new java.net.URI url) ; throws UriSyntaxException
                       (java.net.http.HttpRequest/newBuilder)
                       ;; if exceeded, send will throw HttpTimeoutException
                       (.timeout (java.time.Duration/ofSeconds default-fetch-timeout))
                       (add-headers headers)
                       (.build)) ;; throws IllegalStateException
           client (java.net.http.HttpClient/newHttpClient)]
       ;; throws IoException, InterruptedException, IllegalArgumentException, or SecurityException
       [(.send client request handler) nil])
     (catch Exception e [nil e]))))

(defn unwrap-response
  [[^java.net.http.HttpResponse resp err]]
  (if err
    [nil err]
    (case (.statusCode resp)
      200 [resp nil]
      304 [::unmodified nil]
      404 [nil ::not-found]
      [nil [::unknown-status (.statusCode resp)]])))

;; === Request handling ===

(defn download-to-disk?
  [req]
  (contains? req ::download-target))

(defn disk-handler
  [{^String local ::download-target}]
  (-> local
      (java.nio.file.Path/of (into-array String [])) ; force to use string varargs overload
      (java.net.http.HttpResponse$BodyHandlers/ofFile)))

(defn string-handler
  [_]
  (java.net.http.HttpResponse$BodyHandlers/ofString))

(defn download-req!
  [{remote ::download-url :as req}]
  (log/debug "Downloading request" req)
  ;; TODO: decouple handler and body format (str vs. otherwise) from `download-to-disk?`
  (let [handler (if (download-to-disk? req) (disk-handler req) (string-handler req))
        [resp err] (request remote handler)]
    ;; TODO: call unwrap-response ??
    (if err
      ;; Use different form when we are logging an exception vs our own error to keep it readable -
      ;; log contains a special case for throwables.
      (if (isa? (type err) Throwable)
        (log/error "Error downloading" req err)
        (log/error "Error" err "downloading" req)))
    ;; Maybe respond
    (if (request/expects-response? req)
      (let [response (assoc req ::http-response resp)]
        (request/send-response! response)))))

;; === Timer loop ===

(defn queue-expiring
  [schedule-ref queue now]
  (into queue (timer/expire-items schedule-ref now)))

(defn safe-pop
  [queue]
  (if (> (bounded-count 2 queue) 1)
    (pop queue)
    (empty queue)))

(defn maybe-pop-from-ref!
  [ref]
  ;; if anything is in ref, pop it
  (let [[old new] (swap-vals! ref safe-pop)]
    (if (and (some? (peek old)) ; if old had something...
             (not (= (peek old) (peek new)))) ; and it is different than what new has...
      (peek old) ;; return what was popped
      nil)))

(defn timer-loop
  [schedule-ref asap-ref]
  (loop [queue clojure.lang.PersistentQueue/EMPTY]
    (let [exp-q (queue-expiring schedule-ref queue (System/nanoTime))]
      ;; If there is something in the queue (items that came from sched-ref) download it. If not,
      ;; try to take an item from asap-ref.
      (if-let [item (if-let [it (peek exp-q)]
                      it
                      (maybe-pop-from-ref! asap-ref))]
        (download-req! item))
      ;; Wait for ratelimit
      ;;
      ;; TODO: if we want to support timer times with more precision than `ms-between-download`, we
      ;; would need multiple little sleeps and with expiries, until they all add up to it.
      (Thread/sleep ms-between-download)
      ;; Repeat
      ;;
      ;; TODO: allow orderly exit
      (recur (safe-pop exp-q)))))

;; === Service ===

(defn handle-item
  [sched-ref asap-ref item]
  (if (contains? item ::time-nano)
    (timer/schedule sched-ref (::time-nano item) item) ;; schedule item
    (swap! asap-ref conj item))) ;; put item into asap-ref

(defrecord RateLimitDownloaderService [thread sched-ref asap-ref]
  service/Service
  (handle-item! [this item] (handle-item sched-ref asap-ref item))
  (stop [this] nil)) ;; TODO TODO TODO

(defn init-service
  [ctx]
  (let [sched-ref (atom (sorted-map))
        asap-ref (atom clojure.lang.PersistentQueue/EMPTY)
        ^Runnable entry-point (partial timer-loop sched-ref asap-ref)
        th (Thread. entry-point)
        service (->RateLimitDownloaderService th sched-ref asap-ref)]
    (.start th)
    {::rate-limited-downloader service}))

(defn init
  "Wrap init-service so this can be initted like async-dl. Stopgap until services are properly
  integrated into core."
  [ctx]
  (update (:state ctx) :service-map merge (init-service ctx)))