(ns channeler.thread-manager
  (:require [channeler.config :as config]
            [channeler.chan-th :as chan-th]
            [clojure.tools.logging :as log]))

(def thread-handles (atom {}))

(defn thread-present?
  [board-name thread-id]
  (contains? @thread-handles [board-name thread-id]))

(defn context-for-thread
  [general-context additional-conf]
  (update-in general-context [:conf] config/conf-seq additional-conf))

(defn spawn-thread!
  "Start a java thread to process a chan thread. Returns a handle referencing it, to be used in
  eg. wait-for-completion."
  [context board-name thread-id]
  (if (thread-present? board-name thread-id)
    (log/info "Ignoring" board-name "thread" thread-id "because it is already in the system")
    (let [th-ref (chan-th/create context [board-name thread-id])]
      (swap! thread-handles assoc [board-name thread-id] th-ref))))

(defn add-thread!
  "Spawn thread and return handle (see spawn-thread!), optionally adding per-thread config."
  ([context board-name thread-id] (spawn-thread! context board-name thread-id))
  ([context board-name thread-id per-thread-conf]
   (spawn-thread! (context-for-thread context per-thread-conf) board-name thread-id)))

(defn wait-for-completion
  [handles]
  (let [completions (map #(get @% ::completed) handles)]
    (if (every? identity completions)
      true
      (do (log/debug "Waiting for" (count (filter not completions)) "threads to complete")
          (Thread/sleep (* 1000 10)) ; 10 seconds
          (recur handles)))))

(defn wait-for-all-to-complete
  []
  (let [handles (vals @thread-handles)]
    (wait-for-completion handles)
    (log/info "All threads completed")))
