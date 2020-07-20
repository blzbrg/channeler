(ns channeler.log-config
  (:require [clojure.tools.logging :as log]))

(defn rewrite-unconditionally
  "Create BiFunction for use in LogManager.updateCondiguration mapper that will always map to the
  desired value, ignoring the new and old value."
  [desired-val]
  (reify java.util.function.BiFunction
    (apply [_ old-val new-val] desired-val)))

(def prop-unchanged
  (reify java.util.function.BiFunction
    (apply [_ _ new-val] new-val)))

(defn replace-mapper
  "Make a mapper passed to java.util.logging.LogManager.updateConfiguration to apply our hardcoded
  default config.

  Note that this cannot be a function with two arguments that is partially applied because the
  resulting partial-fn will not satisfy the appropritate interface."
  [replacements]
  (reify java.util.function.Function
    (apply [_ key] ; ignore "this"
      (if (contains? replacements key)
        (rewrite-unconditionally (replacements key))
        prop-unchanged))))

(defn configure-logging!
  "Configure logging based on (non-raw) config. Does not accept state so that logging can be set up
  before state is constructed."
  [conf]
  (let [manager (java.util.logging.LogManager/getLogManager)
        replacements (if-let [log-path (get-in conf ["logging" "path"])]
                       {"java.util.logging.FileHandler.pattern" log-path}
                       {})]
    ;; If the system property is unset, load from our default in resources, otherwise load from the
    ;; file given in the system properties
    ;;
    ;; use updateConfiguration instead of readConfiguration(InputStream) reading from a defaults
    ;; file in resources, since the docs say that is only for use during initialization of the
    ;; logging module, whereas this code runs later.
    (if (some? (System/getProperty "java.util.logging.config.file"))
      ;; this implicitly loads from the system property
      (.updateConfiguration manager (replace-mapper replacements))
      ;; construct the stream explicitly to load from resources
      (let [prop-stream (-> "log.properties"
                            (clojure.java.io/resource)
                            (clojure.java.io/input-stream))]
        (.updateConfiguration manager prop-stream (replace-mapper replacements))))
    (log/info "Logging initialized")))
