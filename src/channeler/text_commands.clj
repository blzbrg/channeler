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
    :validate [#(map? %) "Must parse to a valid JSON map"]]])

(defn parse-from-arglist
  [args]
  (clojure.tools.cli/parse-opts args parser))

(defn handle-add-thread
  [context {[_ & positional-args] :arguments {per-thread-opts :merge-opts} :options}]
  (if (= (count positional-args) 2)
    (let [[board thread-id] positional-args]
      (thread-manager/add-thread! context board thread-id per-thread-opts))
    (System/exit 1))) ; TODO: GRACE!!

(defn handle-command
  [context {[command & rest] :arguments :as parsed}]
  ;; TODO: handle unrecognized commands gracefully
  (case command
    "add-thread" (handle-add-thread context parsed)
    (log/error "Unrecognized command" command)))
