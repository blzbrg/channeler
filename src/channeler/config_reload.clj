(ns channeler.config-reload
  "Watch a directory. Every time a file is created or edited in this directory, try to parse it as
  json and use it to configure a thread. Invalid configs are ignored, and configs that describe a
  thread which already exists replace the configuration of that thread.

  Subdirectories and anything that is not a normal file are ignored. This means that this does not
  recursively watch.

  An example config for thread 42 in board /b/ writing into dir /tmp/b/42 would look like:
  {\"board\":\"b\", \"thread\":42, \"dir\":\"/tmp/b/42\"}

  Config:
  thread-conf-dir: Path to directory to watch."
  (:require [channeler.thread-manager :as thread-manager]
            [channeler.config :as config]
            [clojure.data.json :as json]
            [clojure.tools.logging :as log]
            [clojure.java.io :as io]))

(defn verify-conf-acceptable
  "Return `false` if `conf` contains an acceptable config, otherwise an error describing the reason
  it is unacceptable. `path` is the path from which the conf was loaded."
  [conf path]
  (let [prefix (str "Config " path)
        mk (fn [key] (str prefix " missing key \"" key "\""))]
    (condp (fn [key conf] (nil? (config/conf-get conf key))) conf
      "board" (mk "board")
      "thread" (mk "thread")
      "dir" (mk "dir")
      false)))

(defn maybe-conf
  "If `file` contains a valid config, return `[conf ...]`, otherwise return `[false error-message]`."
  [{context-conf :conf} ^java.io.File file]
  ;; json-str->config gives nil when it fails to parse, so incomplete/invalid JSON is fine.
  (if-let [raw-thread-conf (config/json-str->config (slurp file))]
    ;; If file loaded/parsed, construct actual conf seq and validate it
    (let [conf (cons [::thread-conf raw-thread-conf] context-conf)
          maybe-err (verify-conf-acceptable conf (.getCanonicalPath file))]
      (if maybe-err
        [false maybe-err]
        [conf nil]))
    ;; File could not load/parse
    ;;
    ;; TODO: communicate exception?
    [false (str "Config " (.getCanonicalPath file) " could not be parsed as JSON")]))


(defn ->watch-service ^java.nio.file.WatchService
  []
  (.newWatchService (java.nio.file.FileSystems/getDefault)))

(defn register
  "Helper to call java.nio.file.Watchable.register. Registers to listen to `event-kinds` events on the
  path."
  [conf-path ^java.nio.file.WatchService watch-service event-kinds]
  (let [path (java.nio.file.Path/of conf-path (into-array String []))]
    (.register path watch-service
               (into-array event-kinds))))

(defn get-changed-files
  "Given a WatchKey, return a seqable of the files changed. If there are none, return an empty seq."
  [^java.nio.file.WatchKey key]
  ;; As long as the WatchEvent.Kind of every event for the key is ENTRY_CREATE, ENTRY_DELETE, or
  ;; ENTRY_MODIFY, java.nio.file.Path.register docs claim the context will be relative path between
  ;; registered dir and changed entry
  (let [^java.nio.file.Path watched-dir-p (.watchable key) ; TODO: cast? check type?
        events (.pollEvents key)]
    (.reset key) ; tell the watch service that we have consumed all the events from this key
    (for [^java.nio.file.WatchEvent event events]
      (do (log/debug "Key" (.watchable key) "gives event" (.context event)
                     "with count" (.count event) "and kind" (.kind event))
          (let [^java.nio.file.Path relative (.context event)] ; Get relative path
            (.toFile (.resolve watched-dir-p relative))))))) ; Get file for aboslute path

(defn handle-changed!
  [context ^java.io.File changed-file]
  (log/debug "handle-changed for" (.getPath changed-file))
  (let [[conf err] (maybe-conf context changed-file)]
    (if conf
      ;; If conf is valid, process it.
      (let [board (config/conf-get conf "board")
            thread (config/conf-get conf "thread")]
        (log/debug "Resulting config:" conf)
        (if (thread-manager/thread-present? board thread)
          ;; If thread is present, reconfigure it
          (do (log/info "Config" (.getCanonicalPath changed-file) "is UPDATED, reconfiguring thread")
              (thread-manager/reconfigure-thread! board thread conf))
          ;; If thread is new, add it
          (do (log/info "Config" (.getCanonicalPath changed-file) "is NEW, creating thread")
              (thread-manager/add-thread! context board thread conf))))
      ;; If conf is invalid, log the error. This is `info` because incomplete configs are ok.
      (log/info err))))

(defn watch-loop
  [context ^java.nio.file.WatchService watch-service]
  (loop [] ; Loop forever
    ;; Block waiting for events. Note: can block forever.
    (let [changed-files (get-changed-files (.take watch-service))]
      ;; Get changed "files" (can include dirs and specials
      (doseq [^java.io.File changed-file changed-files]
        ;; For non-special files (which is platform specific) handle them, for other things, log
        ;; that we are ignoring them
        (if (.isFile changed-file)
          (handle-changed! context changed-file)
          (log/info "Ignoring non-file" (.getPath changed-file)))))
    (recur)))

(defn init
  [{conf :conf :as context}]
  (when-let [conf-path (config/conf-get conf "thread-conf-dir")]
    (log/info "Watching for configuration in" conf-path)
    (let [watch-service (.newWatchService (java.nio.file.FileSystems/getDefault))
          ^Runnable entry-point (partial watch-loop context watch-service)
          th (Thread. entry-point "Config Service")]
      ;; watch conf-path
      (register conf-path watch-service [java.nio.file.StandardWatchEventKinds/ENTRY_CREATE
                                         java.nio.file.StandardWatchEventKinds/ENTRY_MODIFY])
      (.setDaemon th true)
      (.start th)
      th)))
