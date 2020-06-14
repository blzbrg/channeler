(ns channeler.chan-th
  (:require [channeler.transcade :as transcade]
            [clojure.data.json :as json]
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