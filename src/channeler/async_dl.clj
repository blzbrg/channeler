(ns channeler.async-dl
  (:require [clojure.java.io :as io]
            [clojure.core.async :as async]))

(defn dl-request
  [remote local response-chan]
  [remote local response-chan])

(defn handle-request
  [[remote local response-chan]]
  ;; TODO: handle errors
  (with-open [in (io/input-stream remote)
              out (io/output-stream local)]
    (io/copy in out))
  (async/>!! response-chan [::done remote local]))

(defn ^:private conf-accessor
  [state & keys]
  (get-in state (concat [:channeler.state/config "async-dl"] keys)))

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
  [state]
  (let [interval-sec (conf-accessor state "min-sec-between-downloads")
        chan (make-request-chan)
        ;; spawn thread - no need to keep reference to the future, we will never look at the result.
        _ (future (download-loop chan interval-sec))]
    (assoc state :channeler.state/async-dl-chan chan)))

(defn deinit
  "End the state and asynchronous jobs created during init. Shoudld be called when the application
  itends to do an orderly shutdown."
  [{chan :channeler.state/async-dl-chan}]
  ;; closing the channel will cause the download-loop to exit and the thread to end. Returning from
  ;; the main thread won't kill this thread!
  (async/close! chan))