(ns channeler.text-commands
  (:require [clojure.tools.cli]
            [channeler.config :as config]))

(def parser
  [["-m" "--mergeOpts JSON" "JSON structure to merge with the config loaded from files"
    :id :merge-opts
    :default {}
    :parse-fn config/json-str->config
    :validate [#(map? %) "Must parse to a valid JSON map"]]])

(defn parse-from-arglist
  [args]
  (clojure.tools.cli/parse-opts args parser))
