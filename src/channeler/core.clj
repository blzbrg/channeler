(ns channeler.core
  (:gen-class)
  (:require [channeler.state :as state]
            [channeler.chan-th :as chan-th]
            ))

(defn thread-url
  [board thread]
  (format "https://a.4cdn.org/%s/thread/%s.json" board thread))

(defn -main
  [board-name thread-id]
  (let [state (state/initial-state {})]
    (loop [th (chan-th/init-thread state (thread-url board-name thread-id))]
      (chan-th/export-thread state th))))
