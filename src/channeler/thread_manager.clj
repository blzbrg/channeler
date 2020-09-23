(ns channeler.thread-manager
  (:require [channeler.config :as config]
            [channeler.chan-th :as chan-th]))

(def thread-handles (atom {}))

(defn context-for-thread
  [general-context additional-conf]
  (update-in general-context [:conf] config/conf-seq additional-conf))

(defn spawn-thread!
  "Start a java thread to process a chan thread. Returns a handle referencing it, to be used in
  eg. wait-for-completion."
  [context board-name thread-id]
  (let [handle (future (->> (chan-th/init-thread context board-name thread-id)
                            (chan-th/thread-loop context)))]
    (swap! thread-handles #(assoc % [board-name thread-id] handle))
    handle))

(defn add-thread!
  "Spawn thread and return handle (see spawn-thread!), optionally adding per-thread config."
  ([context board-name thread-id] (spawn-thread! context board-name thread-id))
  ([context board-name thread-id per-thread-conf]
   (spawn-thread! (context-for-thread context per-thread-conf) board-name thread-id)))

(defn wait-for-completion
  [handle]
  @handle)

(defn wait-for-all-to-complete
  []
  (doseq [handle (vals @thread-handles)]
    (wait-for-completion handle)))
