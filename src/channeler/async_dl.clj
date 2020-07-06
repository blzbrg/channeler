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

(defn download-loop
  [pull-chan]
  (if-let [request (async/<!! pull-chan)]
    ;; if we got a request, handle it then wait again
    (do (handle-request request)
        (recur pull-chan))
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
  (let [chan (make-request-chan)
        ;; spawn thread - do not wait for end since we assume it never ends
        _ (future (download-loop chan))]
    (assoc state :channeler.state/async-dl-chan chan)))