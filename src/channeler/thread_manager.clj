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
  (update-in general-context [:conf] conj [::thread-conf additional-conf]))

(defn spawn-thread!
  "Start a java thread to process a chan thread. Returns a handle referencing it, to be used in
  eg. wait-for-completion."
  [context board-name thread-id]
  (if (thread-present? board-name thread-id)
    ;; Ignore thread if we already have it
    (log/info "Ignoring" board-name "thread" thread-id "because it is already in the system")
    (let [[ok err] (chan-th/verify-potential-thread context [board-name thread-id])]
      (if err
        ;; Reject thread if the thread verification rejects it
        (log/error "Ignoring" board-name "thread" thread-id "because" err)
        ;; Checks passed, creater the thread
        (let [th-ref (chan-th/create context [board-name thread-id])]
          (swap! thread-handles assoc [board-name thread-id] th-ref))))))

;; Two thread APIs since remote-command needs add-thread! and config-reload needs add-configured-thread!

(defn add-thread!
  "Spawn thread and return handle (see spawn-thread!), optionally adding per-thread config."
  ([context board-name thread-id] (spawn-thread! context board-name thread-id))
  ([context board-name thread-id per-thread-conf]
   (spawn-thread! (context-for-thread context per-thread-conf) board-name thread-id)))

(defn add-configured-thread!
  "Spawn thread with given associative conf seq, and return handle."
  [context board-name thread-id conf]
  (spawn-thread! (assoc context :conf conf) board-name thread-id))

(defn reconfigure-thread!
  [board-name thread-id new-conf]
  (let [thread-ref (@thread-handles [board-name thread-id])]
    (send thread-ref assoc :channeler.chan-th/conf new-conf)))

(defn wait-for-completion
  [handles]
  (let [incomplete (->> handles
                        (map deref)
                        (filter #(not (chan-th/completed? %))))]
    (if (empty? incomplete)
      true
      (do (log/debug "Waiting for" (map #(select-keys % [::chan-th/board ::chan-th/id]) incomplete)
                     "to complete")
          ;; TODO: bad hack
          (Thread/sleep (* 1000 10)) ; 10 seconds
          (recur handles)))))

(defn wait-for-all-to-complete
  []
  (let [handles (vals @thread-handles)]
    (wait-for-completion handles)
    (log/info "All threads completed")))
