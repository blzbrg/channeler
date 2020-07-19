(ns channeler.core
  (:gen-class)
  (:require [channeler.state :as state]
            [channeler.plugin-loader :as plugin-loader]
            [channeler.chan-th :as chan-th]
            [channeler.config :as config]
            [channeler.async-dl :as async-dl]
            [clojure.tools.cli :as cli]))

(defn eprintln
  [& args]
  (binding [*out* *err*] (apply println args)))

(defn do-cli
  [args]
  (let [opts (cli/parse-opts args config/override-options)]
    (if-let [errs (opts :errors)]
      (do (eprintln errs)
          (System/exit 1))
      opts)))

(defn -main
  [& args]
  (let [{cli-opts :options [board-name thread-id] :arguments} (do-cli args)
        raw-conf (config/from-file)
        conf (config/incorporate-cli-options raw-conf cli-opts)
        plugin-configs (conf "plugins")
        state (-> (state/initial-state conf)
                  (async-dl/init)
                  (plugin-loader/load-plugins plugin-configs))]
    (loop [th (chan-th/init-thread state board-name thread-id)]
      (chan-th/export-thread state th)
      (Thread/sleep (* 10 1000))
      (println "Updating" (th ::chan-th/url))
      (recur (chan-th/update-posts state th)))
    (async-dl/deinit state)))