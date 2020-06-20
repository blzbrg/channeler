(ns channeler.config
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]))

(defn ^:prvate usable-env-val?
  [val]
  (and (string? val) (> count 0)))

(defn ^:private default-paths
  ([] (default-paths 0))
  ;; this case allows new ones to be added without doing any crazy if-else chains
  ([n] (case (int n) ; coerce to silence performance warning
         ;; if $XDG_CONFIG_HOME exists try a path based on it, if not skip right to  the next one
         0 (lazy-seq (let [conf-home-var (System/getenv "XDG_CONFIG_HOME")]
                       (if (usable-env-val? conf-home-var)
                         (cons (io/file conf-home-var "channeler" "config.json")
                               (default-paths 1))
                         (default-paths 1))))
         ;; use ~/.config/ when $XDG_CONFIG_HOME is unset. This complies with
         ;; https://specifications.freedesktop.org/basedir-spec/basedir-spec-latest.html#referencing
         1 (lazy-seq (list (io/file (System/getProperty "user.home") ".config" "channeler" "config.json")))
         ;; failing that, try ~/channeler/config.json
         2 (lazy-seq (list (io/file (System/getProperty "user.home") "channeler" "config.json"))))))


(defn ^:private first-usable-file
  "Return the first file in a seq that exists on the filesystem, or nil if none do."
  [[head & tail]]
  (let [f (io/as-file head)]
    (if (.exists f)
      f
      (if tail (recur tail) nil)))) ; tail will be nil when we get to the end

(defn ^:private load-from-path
  [path]
  (json/read (io/reader path)))

(def default-conf
  {"plugins" {"channeler.image-download" {"type" "clojure-ns"}}})

(defn from-file
  "Load a config, either from a provided path, or from the default locations (documented in
  default-paths)."
  ([path-on-cmdline] (load-from-path path-on-cmdline))
  ([] (if-let [path (first-usable-file (default-paths))]
        (load-from-path path)
        default-conf)))