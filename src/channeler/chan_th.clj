(ns channeler.chan-th
  (:require [channeler.transcade :as transcade]
            [channeler.state :as state]
            [clojure.data.json :as json]
            [clojure.walk :as walk]
            [clojure.core.async :as async]
            [clojure.tools.logging :as log]
            [clojure.java.io :as io]
            ))

(defn ^:private new-posts
  [old-posts new-posts]
  ;; TODO: this is very hacky
  (drop (count old-posts) new-posts))

(defn ^:private new-posts-from-json
  [old-posts new-thread-json]
  (new-posts old-posts (new-thread-json "posts")))

(defn ^:private fetch
  [url]
  (let [request (-> (java.net.http.HttpRequest/newBuilder)
                    (.GET)
                    (.uri (new java.net.URI url))
                    ;; TODO: send If-modified-since
                    (.build))
        client (java.net.http.HttpClient/newHttpClient)
        ;; all the exceptions send throws should never happen - fail-fast if they do
        response (.send client request (java.net.http.HttpResponse$BodyHandlers/ofString))]
    (if (= (.statusCode response) 200)
      (json/read-str (.body response))
      nil)))

(defn ^:private post-routine
  [post-transcade th post]
  (transcade/coroutine-loop post-transcade th post))

(defn ^:private process-posts
  "Take posts in json format and process them into our format, by using
  the post-transcade"
  [{post-transcade :post-transcade} th json-posts]
  (->> json-posts
       ;; channel for each post (fed by coroutine)
       (map (partial post-routine post-transcade th))
       ;; get only one thing
       (map (partial async/take 1))
       ;; collect into one channel with the results of all the others
       (async/map list)
       ;; block our thread waiting for the channel. This had better not be in a coroutine!
       (async/<!!)))

(defn ^:private eliminate-symbol-keys
  [m]
  (into {} (filter (fn [[k v]] (not (keyword? k))) m)))

(defn ^:private trim-for-export
  [coll]
  (walk/prewalk #(if (map? %) (eliminate-symbol-keys %) %) coll))

(defn ^:private export-file
  [{dir ::state/dir} {thread-id ::id}]
  (let [fname (str thread-id ".json")]
    (io/file dir fname)))

(def ^:private write-options
  (list :escape-unicode false :escape-js-separators false
        :escape-slash false))

(defn export-thread
  [state th]
  (with-open [w (clojure.java.io/writer (export-file state th))]
    (apply json/write (trim-for-export th) w write-options)))

(defn thread-url
  ([{id ::id board ::board}] (thread-url board id))
  ([board id] (format "https://a.4cdn.org/%s/thread/%s.json" board id)))

(defn init-thread
  [state board-name thread-id]
  (let [url (thread-url board-name thread-id)
        _ (log/info "Initializing thread" url)
        th (-> (fetch url)
               (assoc ::id thread-id)
               (assoc ::board board-name))
        posts (process-posts state th (th "posts"))]
    (assoc th "posts" posts)))

(defn update-posts
  "Uses side effects to update the passed thread, applying post transforms, and returns the new
  version"
  [state {old-posts "posts" :as old-thread}]
  (if-let [raw-new-thread (fetch (thread-url old-thread))]
    (let [raw-new-posts (new-posts-from-json old-posts raw-new-thread)]
      (if (empty? raw-new-posts)
        ;; thread unchanged
        old-thread
        ;; process new posts
        (->> raw-new-posts
             (process-posts state old-thread)
             (conj old-posts)
             (assoc old-thread "posts"))))
    ;; thread has 404d
    nil))

(defn thread-loop
  "Refresh thread, infinitely, inline. Sleeps thread to wait."
  [state th]
  (export-thread state th)
  (let [wait (get-in state [:channeler.state/config "thread" "min-sec-between-refresh"])]
    (Thread/sleep (* wait 1000)))
  (log/info "Updating" (thread-url th)) ; for now, just use URL to represent thread
  (if-let [new-th (update-posts state th)]
    (recur state new-th)
    (log/info "Thread is 404" (thread-url th))))
