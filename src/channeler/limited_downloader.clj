(ns channeler.limited-downloader
  "Rate limited downloader."
  (:require [channeler.timer :as timer]
            [channeler.service :as service]
            [channeler.request :as request]
            [channeler.config :refer [conf-get]]
            [clojure.java.io :as io]
            [clojure.tools.logging :as log]))

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
  "Perform an HTTP request given URL and `java.net.http.HttpResponse$BodyHandler`. Returns `[response
  nil] if success or [nil e] where e is the exception thrown during the attempt."
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
        [resp err] (if-let [headers (get req ::headers)]
                     (request remote handler headers)
                     (request remote handler))]
    (if err
      ;; Use different form when we are logging an exception vs our own error to keep it readable -
      ;; log contains a special case for throwables.
      (if (isa? (type err) Throwable)
        (log/error "Error downloading" remote err)
        (log/error "Error" err "downloading" remote)))
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

(def min-sleep-ms
  "Minimum time to sleep in the timer loop. This is effectively the granularity at which
  `ms-between-download` is checked."
  500)

(defn timer-loop-dequeue-and-selection
  [now old-queue schedule-ref asap-ref]
  (let [new-queue (queue-expiring schedule-ref old-queue now)]
    ;; If there is something in the queue (items that came from sched-ref) download it. If not, try
    ;; to take an item from asap-ref.
    [new-queue
     (if-let [it (peek new-queue)]
       it
       (maybe-pop-from-ref! asap-ref))]))

(defn timer-loop
  [sec-between-download time-source stop-ref schedule-ref asap-ref]
  (loop [queue clojure.lang.PersistentQueue/EMPTY]
    (let [now (time-source)
          [exp-q item] (timer-loop-dequeue-and-selection now queue schedule-ref asap-ref)]
      (if item
        (download-req! item))
      ;; Wait until minimum time for ratelimit has passed.
      ;;
      ;; Checking the time-source rather than using sleep for timing has two advantages:
      ;; 1. We do not count on the accuracy of sleep.
      ;; 2. time-source can be mocked (or, theoretically, used in prod differently) without
      ;;    replacing sleep.
      (let [target-time (+ now (-> sec-between-download
                                   (java.time.Duration/ofSeconds)
                                   (.toNanos)))]
        (loop []
          (when (< (time-source) target-time)
            (Thread/sleep min-sleep-ms)
            (recur)))
        ;; Check stop-ref to see if we need to stop. If not, repeat.
        ;;
        ;; Other values of stop-ref could be used in the future to eg. stop after draining
        ;; everything in exp-q.
        (if (not (and (realized? stop-ref) (= ::next-yield @stop-ref)))
          (recur (safe-pop exp-q)))))))

;; === Service ===

(defn handle-item
  [sched-ref asap-ref item]
  (if (contains? item ::time-nano)
    (timer/schedule sched-ref (::time-nano item) item) ;; schedule item
    (swap! asap-ref conj item))) ;; put item into asap-ref

(defrecord RateLimitDownloaderService [^Thread thread stop-ref sched-ref asap-ref]
  service/Service
  (handle-item! [this item] (handle-item sched-ref asap-ref item))
  (stop [this] (do (deliver stop-ref ::next-yield)
                   (.interrupt thread)))) ; break out of sleep

(defn init-service
  ;; time source can be passed to replace System/nanoTime with a mock for virtual time in tests.
  ([ctx] (init-service ctx timer/nano-mono-time-source))
  ([ctx time-source]
   (let [stop-ref (promise)
         sched-ref (atom (sorted-map))
         asap-ref (atom clojure.lang.PersistentQueue/EMPTY)
         sec-between-download (conf-get (:conf ctx)
                                        "network-rate-limit" "min-sec-between-downloads")
         ^Runnable entry-point (partial timer-loop sec-between-download time-source stop-ref
                                        sched-ref asap-ref)
         th (Thread. entry-point "Limited Downloader download service")
         service (->RateLimitDownloaderService th stop-ref sched-ref asap-ref)]
     (.setDaemon th true)
     (.start th)
     {::rate-limited-downloader service})))

(defn init
  "Wrap init-service so this can be initted like async-dl. Stopgap until services are properly
  integrated into core."
  [ctx]
  (update (:state ctx) :service-map merge (init-service ctx)))
