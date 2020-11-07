(ns channeler.text-commands
  (:require [clojure.tools.cli]
            [clojure.tools.logging :as log]
            [channeler.config :as config]
            [channeler.thread-manager :as thread-manager]))

(def parser
  [["-m" "--mergeOpts JSON" "JSON structure to merge with the config loaded from files"
    :id :merge-opts
    :default {}
    :parse-fn config/json-str->config
    :validate [#(map? %) "Must parse to a valid JSON map"]]
   ["-d" "--daemon" "Run as a daemon, see remote-control for docs on controlling."
    :id :daemon]])

(defn parse-from-arglist
  [args]
  (clojure.tools.cli/parse-opts args parser))

(defn is-command?
  "Takes the parsed args (from parse-from-arglist), returns whether it is a command. For example,
  'add-thread ...' is a command and '-d' is not."
  [parsed]
  (not-empty (:arguments parsed)))

(defn handle-add-thread
  [context {[_ & positional-args] :arguments {per-thread-opts :merge-opts} :options}]
  (if (= (count positional-args) 2)
    (let [[board thread-id] positional-args]
      (thread-manager/add-thread! context board thread-id per-thread-opts))
    (log/error "Expected two positional args to add-thread but got:" positional-args)))

(defn handle-command
  [context {[command & rest] :arguments :as parsed}]
  (if (:errors parsed)
    (log/error "Could not understand command" (:arguments parsed) "due to" (:errors parsed))
    (case command
      "add-thread" (handle-add-thread context parsed)
      (log/error "Unrecognized command" command))))
