(ns channeler.core
  (:gen-class)
  (:require [channeler.state :as state]
            [channeler.plugin-loader :as plugin-loader]
            [channeler.chan-th :as chan-th]
            ))

(defn -main
  [board-name thread-id]
  (let [plugin-configs () ; TODO: non-empty plugin configs
        state (-> {}
                  (state/initial-state)
                  (plugin-loader/load-plugins plugin-configs))]
    (loop [th (chan-th/init-thread state board-name thread-id)]
      (chan-th/export-thread state th))))
