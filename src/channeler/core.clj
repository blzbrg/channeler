(ns channeler.core
  (:gen-class)
  (:require [channeler.plugin-loader :as plugin-loader]
            [channeler.chan-th :as chan-th]
            [channeler.config :as config]
            [channeler.async-dl :as async-dl]
            [channeler.log-config :as log-config]
            [channeler.text-commands :as text-commands]
            [clojure.tools.logging :as log]))

(defn eprintln
  [& args]
  (binding [*out* *err*] (apply println args)))

(defn do-cli
  [args]
  (let [cli-opts (text-commands/parse-from-arglist args)]
    (if (:errors cli-opts) ; there are errors if this value is truthy
      (do (eprintln (:errors cli-opts))
          (System/exit 1))
      cli-opts)))

(def initial-state
  {:post-transcade {:transformers []}}) ; empty transcade so there is no error if none are registered

(defn -main
  [& args]
  (let [{cli-opts :options [board-name thread-id] :arguments} (do-cli args)
        raw-conf (config/from-file)
        conf (config/incorporate-cli-options raw-conf cli-opts)
        _ (log-config/configure-logging! conf) ; run for side effect, want to be before state
        ;; TODO: cleaner "threading" of updating state. This is ugly!
        context (as-> {:state initial-state :conf conf} context
                    (assoc context :state (async-dl/init context))
                    (assoc context :state (plugin-loader/load-plugins context)))
        th (chan-th/init-thread context board-name thread-id)]
    (chan-th/thread-loop context th)
    (async-dl/deinit context)))