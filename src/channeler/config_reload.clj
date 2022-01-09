(ns channeler.config-reload
  (:require [channeler.thread-manager :as thread-manager]
            [channeler.config :as config]
            [clojure.data.json :as json]
            [clojure.tools.logging :as log]
            [clojure.java.io :as io]))

(defn ->watch-service ^java.nio.file.WatchService
  []
  (.newWatchService (java.nio.file.FileSystems/getDefault)))

(defn register
  "Helper to call java.nio.file.Watchable.register. Registers the path for
  java.nio.file.StandardWatchEventKinds/ENTRY_CREATE."
  [conf-path ^java.nio.file.WatchService watch-service]
  (let [path (java.nio.file.Path/of conf-path (into-array String []))]
    (.register path watch-service
               (into-array [java.nio.file.StandardWatchEventKinds/ENTRY_CREATE]))))

(defn get-changed-paths
  "Given a WatchKey, return a seqable of the paths changed. If there are none, return an empty seq."
  [^java.nio.file.WatchKey key]
  ;; As long as the WatchEvent.Kind of every event for the key is ENTRY_CREATE, ENTRY_DELETE, or
  ;; ENTRY_MODIFY, java.nio.file.Path.register docs claim the context will be relative path between
  ;; registered dir and changed entry
  (let [^java.nio.file.Path watched-dir-p (.watchable key) ; TODO: cast? check type?
        events (->> key
                    (.pollEvents) ; list of events
                    (map (fn [^java.nio.file.WatchEvent event] (.context event))) ; context from each
                    ;; Make each relative path into an absolute path to the changed entry
                    (map (fn [^java.nio.file.Path relative-to-change]
                           (.resolve watched-dir-p relative-to-change))))]
    (.reset key) ; tell the watch service that we have consumed all the events from this key
    events))

(defn handle-changed!
  [context ^java.nio.file.Path changed-path]
  (log/debug "Updating config in" changed-path)
  (let [changed-file (.toFile changed-path)]
    (let [conf (config/json-str->config (slurp changed-file))
          {board "board" thread "thread"} conf]
      (log/info "Config" (.getCanonicalPath changed-file) "is NEW, creating thread")
      (thread-manager/add-thread! context board thread conf))))

(defn watch-loop
  [context ^java.nio.file.WatchService watch-service]
  (loop []
    ;; Block waiting for events. Note: can block forever.
    (let [changed-paths (get-changed-paths (.take watch-service))]
      (log/debug "Changed paths:" changed-paths)
      ;; TODO: gracefully handle things that we cannot process as a config change (eg. directories
      ;; being created)
      (doseq [changed-p changed-paths]
        (handle-changed! context changed-p)))
    (recur)))

(defn init
  [{conf :conf :as context}]
  (when-let [conf-path (config/conf-get conf "thread-conf-dir")]
    (log/info "Watching for configuration in" conf-path)
    (let [watch-service (.newWatchService (java.nio.file.FileSystems/getDefault))
          ^Runnable entry-point (partial watch-loop context watch-service)
          th (Thread. entry-point "Config Service")]
      (register conf-path watch-service) ; watch conf-path
      (.setDaemon th true)
      (.start th)
      th)))
