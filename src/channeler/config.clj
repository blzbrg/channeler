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
         1 (lazy-seq (cons (io/file (System/getProperty "user.home")
                                    ".config" "channeler" "config.json")
                           (default-paths 2)))
         ;; failing that, try ~/channeler/config.json
         2 (lazy-seq (cons (io/file (System/getProperty "user.home") "channeler" "config.json")
                           nil)))))


(defn ^:private first-usable-file
  "Return the first file in a seq that exists on the filesystem, or nil if none do."
  [path-seq]
  (let [f (io/as-file (first path-seq))]
    (if (.exists f)
      f
      (if-let [tail (next path-seq)]
        (recur tail)
        nil))))

(defn json-str->config
  [json-str]
  (try (json/read-str json-str :eof-error? false :eof-value nil)
       (catch Exception e nil)))

(def default-conf
  {"plugins" {"channeler.image-download" {"type" "clojure-ns"}}
   "async-dl" {"min-sec-between-downloads" 1}
   "thread" {"min-sec-between-refresh" 10
             "no-new-posts-refresh-backoff" {"backoff-strategy" "exponential"
                                             "max-sec-between-refresh" 300}}})

(defn conf-seq
  "Create a config seq (for use with conf-get). Given a baseline-conf and a additional-conf, returns a
  sequence where additional-conf comes before baseline-conf."
  [baseline-conf additional-conf]
  (cons additional-conf
        (if (seq? baseline-conf) baseline-conf (list baseline-conf))))

(defn incorporate-cli-options
  [conf {merge-opts :merge-opts}]
  (conf-seq conf merge-opts))

(defn from-file
  "Load a config, either from a provided path, or from the default locations (documented in
  default-paths). If path is nil, just use the default conf."
  ([] (from-file (first-usable-file (default-paths))))
  ([path] (if (some? path)
            (conf-seq default-conf
                      (json/read (io/reader path)))
            default-conf)))

(defn conf-get
  "Try the sequence of configs looking for one that contains a value at the \"path\" described by
  keys. Each key in the \"path\" is a key into a map, matching the semantics of get-in."
  [config & keys]
  (if (map? config)
    ;; if there is just one config, get from it directly
    (get-in config keys)
    ;; if there is a sequence of configs, look through them for one that contains it
    (loop [[individual-conf & rest] config]
      ;; if this item in the conf sequence is not a map, assume it can be derefed. This allows
      ;; multiple threads to share a global config easily through an atom.
      (let [eff-conf (if (map? individual-conf) individual-conf @individual-conf)]
        (let [val (get-in eff-conf keys)]
          (if (nil? val)
            (if (some? rest)
              (recur rest) ; if it's missing, keep looping
              nil) ;; if no more confs left, terminate with unfound
            val)))))) ; if it's present, terminate with the value