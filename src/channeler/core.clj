(ns channeler.core
  (:gen-class)
  (:require [channeler.state :as state]
            [channeler.plugin-loader :as plugin-loader]
            [channeler.chan-th :as chan-th]
            [channeler.config :as config]))

(defn -main
  [board-name thread-id]
  (let [conf (config/from-file)
        plugin-configs (conf "plugins")
        state (-> {}
                  (state/initial-state)
                  (plugin-loader/load-plugins plugin-configs))]
    (loop [th (chan-th/init-thread state board-name thread-id)]
      (chan-th/export-thread state th))))
