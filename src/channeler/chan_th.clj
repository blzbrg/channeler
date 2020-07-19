(ns channeler.chan-th
  (:require [channeler.transcade :as transcade]
            [channeler.state :as state]
            [clojure.data.json :as json]
            [clojure.walk :as walk]
            [clojure.core.async :as async]
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
  (json/read (clojure.java.io/reader url)))

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

(defn ^:private export-path
  [{dir ::state/dir} {posts "posts"}]
  (format "%s/%d.json" dir ((first posts) "no")))

(def ^:private write-options
  (list :escape-unicode false :escape-js-separators false
        :escape-slash false))

(defn export-thread
  [state th]
  (with-open [w (clojure.java.io/writer (export-path state th))]
    (apply json/write (trim-for-export th) w write-options)))

(defn thread-url
  [board thread]
  (format "https://a.4cdn.org/%s/thread/%s.json" board thread))

(defn init-thread
  [state board-name thread-id]
  (let [url (thread-url board-name thread-id)
        th (-> (fetch url)
               (assoc ::url url)
               (assoc ::board board-name))
        posts (process-posts state th (th "posts"))]
    (assoc th "posts" posts)))

(defn update-posts
  "Uses side effects to update the passed thread, applying post transforms, and returns the new
  version"
  [state {url ::url old-posts "posts" :as old-thread}]
  (let [raw-new-posts (new-posts-from-json old-posts (fetch url))]
    (if (empty? raw-new-posts)
      ;; thread unchanged
      old-thread
      ;; process new posts
      (->> (new-posts-from-json old-posts (fetch url))
           (process-posts state old-thread)
           (conj old-posts)
           (assoc old-thread "posts")))))