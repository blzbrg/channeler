(ns channeler.core
  (:gen-class)
  (:require [channeler.state :as state]
            [channeler.plugin-loader :as plugin-loader]
            [channeler.chan-th :as chan-th]
            ))

(defn thread-url
  [board thread]
  (format "https://a.4cdn.org/%s/thread/%s.json" board thread))

(defn -main
  [board-name thread-id]
  (let [plugin-configs () ; TODO: non-empty plugin configs
        state (-> {}
                  (state/initial-state)
                  (plugin-loader/load-plugins plugin-configs))]
    (loop [th (chan-th/init-thread state (thread-url board-name thread-id))]
      (chan-th/export-thread state th))))
