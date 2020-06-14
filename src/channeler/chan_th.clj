(ns channeler.chan-th
  (:require [channeler.transcade :as transcade]
            [channeler.state :as state]
            [clojure.data.json :as json]
            [clojure.walk :as walk]
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

(defn ^:private process-posts
  "Take posts in json format and process them into our format, by using
  the post-transcade"
  [{post-transcade :post-transcade} json-posts]
  (map (fn [post] (transcade/inline-loop post-transcade post)) json-posts))

(defn ^:private eliminate-symbol-keys
  [m]
  (into {} (filter (fn [[k v]] (not (keyword? k))) m)))

(defn ^:private trim-for-export
  [_ coll]
  (walk/prewalk #(if (map? %) (eliminate-symbol-keys %) %) coll))

(defn ^:private export-path
  [{dir ::state/dir} {posts "posts"}]
  (format "%s/%d.json" dir ((first posts) "no")))

(defn export-thread
  [state th]
  (with-open [w (clojure.java.io/writer (export-path state th))]
    (json/write th w)))

(defn init-thread
  [state url]
  (let [th (assoc (fetch url) ::url url)
        posts (process-posts state (th "posts"))]
    (assoc th "posts" posts)))

(defn update-posts
  "Uses side effects to update the passed thread, applying post transforms, and returns the new
  version"
  [state {url ::url old-posts "posts" :as old-thread}]
  (let [raw-new-posts (new-posts-from-json old-posts (fetch url))
        processed-new-posts (process-posts state raw-new-posts)]
    (assoc old-thread "posts" (conj old-posts processed-new-posts))))