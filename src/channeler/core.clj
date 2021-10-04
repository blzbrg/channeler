(ns channeler.core
  (:gen-class)
  (:require [channeler.plugin-loader :as plugin-loader]
            [channeler.chan-th :as chan-th]
            [channeler.config :as config]
            [channeler.limited-downloader :as limited-downloader]
            [channeler.log-config :as log-config]
            [channeler.remote-control :as remote-control]
            [channeler.text-commands :as text-commands]
            [channeler.thread-manager :as thread-manager]
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
  (let [{cli-opts :options :as parsed} (do-cli args)
        raw-conf (config/from-file)
        ;; note that this conf is computed when the whole system starts, and is the -m conf, and
        ;; then the from-file conf, in a sequence.
        ;;
        ;; Additionally, when only a single thread is being added (rather than running a daemon that
        ;; handles multiple threads), the per-thread conf IS the -m conf, but it is still added to
        ;; the sequence a second time by handle-add-thread.
        conf (config/incorporate-cli-options raw-conf cli-opts)
        _ (log-config/configure-logging! conf) ; run for side effect, want to be before state
        ;; TODO: cleaner "threading" of updating state. This is ugly!
        context (as-> {:state initial-state :conf conf} context
                  ;; note that order of initialization is very sensitive, since remote-control will
                  ;; capture the state at the time it is initted.
                  (assoc context :state (plugin-loader/load-plugins context))
                  (assoc context :state (limited-downloader/init context)))]
    ;; if it is present, handle the text command. Do this even if daemon is called for.
    (if (text-commands/is-command? parsed)
      (text-commands/handle-command context parsed))
    (if (:daemon cli-opts)
      ;; if daemon is called for, start it and wait forever
      (let [context (assoc context :state (remote-control/init context))]
        @(promise)) ; wait forever. TODO: sane finishing logic
      ;; otherwise, wait for anything started by handle-command then exit.
      (thread-manager/wait-for-all-to-complete))
    (shutdown-agents)))
