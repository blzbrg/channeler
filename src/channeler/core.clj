(ns channeler.core
  (:gen-class)
  (:require [channeler.static-config :as  static-config]
            [channeler.chan-th :as chan-th]
            ))

(defn thread-url
  [board thread]
  (format "https://a.4cdn.org/%s/thread/%s.json" board thread))

(defn -main
  [board-name thread-id]
  (let [static-conf static-config/default]
    (loop [th (chan-th/init-thread static-conf (thread-url board-name thread-id))]
      (Thread/sleep (* 1000 10))
      (recur (chan-th/update-posts static-conf th)))))
