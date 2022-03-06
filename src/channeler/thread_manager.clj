(ns channeler.thread-manager
  (:require [channeler.config :as config]
            [channeler.chan-th :as chan-th]
            [clojure.tools.logging :as log]))

(def thread-handles (atom {}))

(defn thread-present?
  [board-name thread-id]
  (contains? @thread-handles [board-name thread-id]))

(defn spawn-thread!
  "Verify and initialize a chan thread. Returns a handle referencing to the newly created thread, or
  nil."
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

(defn add-thread!
  "Verify and spawn thread. Optionally use passed conf, otherwise use conf from context. Returns
  reference if successful, falsey otherwise."
  ([context board-name thread-id]
   (spawn-thread! context board-name thread-id))
  ([context board-name thread-id conf]
   (spawn-thread! (assoc context :conf conf) board-name thread-id)))

(defn reconfigure-thread!
  [board-name thread-id new-conf]
  (let [thread-ref (@thread-handles [board-name thread-id])]
    (send thread-ref assoc :channeler.chan-th/conf new-conf)))
