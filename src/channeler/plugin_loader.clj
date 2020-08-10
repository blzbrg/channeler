(ns channeler.plugin-loader
  (:require [channeler.plugin :as plugin]
            [channeler.config :refer [conf-get]]))

(defn ^:private init-clj-plugin!
  "Given the fully-qualified namespace for a plugin that can be found on
  the classpath, and a config, load that plugin. For example, if
  \"foo.bar\" is passed, will call (foo.bar/plugin-main)"
  [context ns-str plug-conf]
  (require (symbol ns-str))
  (let [fun (resolve (symbol ns-str "plugin-main"))]
    (fun context plug-conf)))

(defn ^:private init-plugins!
  "Switch over plugin types - use the appropriate init fn for each."
  [context]
  (doseq [[name plug-conf] (conf-get (:conf context) "plugins")]
    (case (plug-conf "type")
      "clojure-ns" (init-clj-plugin! context name plug-conf))))

(defn ^:private put-transcades-into-state
  [state plugin-reg]
  (merge state (plugin-reg ::plugin/transcades)))

(defn ^:private plugin-into-state
  [state plugin-reg]
  (put-transcades-into-state state plugin-reg))

(defn load-plugins
  [context]
  (init-plugins! context)
  (plugin-into-state (context :state) @plugin/reg-state))