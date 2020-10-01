(ns channeler.async-dl
  (:require [channeler.config :refer [conf-get]]
            [clojure.java.io :as io]
            [clojure.core.async :as async]
            [clojure.tools.logging :as log]))

(defn dl-request
  [remote local response-chan]
  [remote local response-chan])

(defn handle-request
  [[remote local response-chan]]
  (log/info "Handling request for remote" remote)
  (try (with-open [in (io/input-stream remote)
                   out (io/output-stream local)]
         (io/copy in out))
       (catch Exception e (log/error "Error handling dl request" e)))
  (async/>!! response-chan [::done remote local]))

(defn download-loop
  [pull-chan interval-sec]
  (if-let [request (async/<!! pull-chan)]
    ;; if we got a request, handle it then wait again
    (do (handle-request request)
        ;; Sleep is only ok if we have a thread all to ourselves (no coroutines)!
        ;;
        ;; An alternative aproach is to wait the interval between the _start of downloads or use
        ;; some kind of exponential backoff, but this implementation is 1. simple and 2. trivially
        ;; complies with the 4chan API rules
        (Thread/sleep (* 1000 interval-sec))
        (recur pull-chan interval-sec))
    ;; if the channell is closed, end the loop
    nil))

(defn make-response-chan
  []
  (async/promise-chan))

(defn make-request-chan
  []
  (async/chan 50))

(defn init
  [context]
  (let [interval-sec (conf-get (:conf context) "async-dl" "min-sec-between-downloads")
        chan (make-request-chan)
        ;; spawn thread - keep ref as an interlock on deinit
        fut (future (download-loop chan interval-sec))]
    (log/debug "Interval is" interval-sec)
    (assoc (context :state) ::async-dl-chan chan ::async-dl-fut fut)))

(defn deinit
  "End the state and asynchronous jobs created during init. Shoudld be called when the application
  itends to do an orderly shutdown."
  [context]
  ;; closing the channel will cause the download-loop to exit and the thread to end. Returning from
  ;; the main thread won't kill this thread!
  (async/close! (get-in context [:state ::async-dl-chan]))
  ;; wait for thread to terminate
  @(get-in context [:state ::async-dl-fut])
  (log/info "Async dl thread ended"))