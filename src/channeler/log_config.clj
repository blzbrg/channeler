(ns channeler.log-config
  (:require [clojure.tools.logging :as log]))

(defn prop-rewrite
  [desired-val]
  (reify java.util.function.BiFunction
    (apply [_ old-val new-val] desired-val)))

(def prop-unchanged
  (reify java.util.function.BiFunction
    (apply [_ _ new-val] new-val)))

(defn logger-mapper
  [log-path]
  (let [simple-formatter-rewrite (prop-rewrite "java.util.logging.SimpleFormatter")
        max-verbosity-rewrite (prop-rewrite "FINE")]
  (reify java.util.function.Function
    (apply [_ key] ; ignore this
      ;; for the properties we care about, just overwrite their values (prop-rewrite just ignores
      ;; the new and old value). For other properties, just accept the "new" value (with
      ;; prop-unchanged).
      (case key
        "handlers" (prop-rewrite "java.util.logging.ConsoleHandler,java.util.logging.FileHandler")
        "java.util.logging.FileHandler.pattern" (prop-rewrite log-path)
        "java.util.logging.FileHandler.formatter" simple-formatter-rewrite
        "java.util.logging.FileHandler.level" max-verbosity-rewrite ; most verbose in file
        "java.util.logging.ConsoleHandler.formatter" simple-formatter-rewrite
        "java.util.logging.ConsoleHandler.level" (prop-rewrite "INFO") ; less verbose on stderr
        ".level" max-verbosity-rewrite ; global logger level - most verbose
        prop-unchanged)))))

(defn configure-logging!
  "Configure logging based on (non-raw) config. Does not accept state so that logging can be set up
  before state is constructed. This method can be called multiple times to re-configure the log path
  - so if in the future the construction of state effects the log path, somehow."
  [conf]
  (let [log-path (conf "log-path")
        manager (java.util.logging.LogManager/getLogManager)
        mapper (logger-mapper log-path)]
    ;; use updateConfiguration instead of readConfiguration(InputStream) reading from a defaults
    ;; file in resources, since the docs say that is only for use during initialization of the
    ;; logging module, whereas this code runs later.
    (. manager updateConfiguration mapper)
    (log/info "Logging initialized")))
