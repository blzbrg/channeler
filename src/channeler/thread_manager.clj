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
    (let [init-prom (promise)
          handle (future (if-let [th (chan-th/init context board-name thread-id)]
                           (do (deliver init-prom :initted)
                               (->> th
                                    (chan-th/process-current-posts context)
                                    (chan-th/thread-loop context)))
                           (deliver init-prom :not-initted)))]
      (if (= @init-prom :not-initted)
        :not-initted ; init-thread is responsible for logging why it did not init
        (do (swap! thread-handles #(assoc % [board-name thread-id] handle))
            handle)))))

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
  (let [handles (vals @thread-handles)]
    (log/debug "Waiting for" (count handles) "threads to complete")
    (doseq [handle handles]
      (wait-for-completion handle))))
