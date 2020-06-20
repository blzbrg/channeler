(ns channeler.plugin-loader
  (:require [channeler.plugin :as plugin]
            ))

(defn ^:private init-clj-plugin!
  "Given the fully-qualified namespace for a plugin that can be found on
  the classpath, and a config, load that plugin. For example, if
  \"foo.bar\" is passed, will call (foo.bar/plugin-main)"
  [ns-str plug-conf]
  (require (symbol ns-str))
  (eval (list (symbol ns-str "plugin-main") plug-conf)))

(defn ^:private init-plugins!
  "Switch over plugin types - use the appropriate init fn for each."
  [plug-confs]
  (doseq [[name conf] plug-confs]
    (case (conf "type")
      "clojure-ns" (init-clj-plugin! name conf))))

(defn ^:private put-transcades-into-state
  [state plugin-reg]
  (merge state (plugin-reg ::plugin/transcades)))

(defn ^:private plugin-into-state
  [state plugin-reg]
  (put-transcades-into-state state plugin-reg))

(defn load-plugins
  [state plug-confs]
  (init-plugins! plug-confs)
  (plugin-into-state state @plugin/reg-state))