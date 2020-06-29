(ns channeler.image-download
  (:require [channeler.plugin :as plugin]
            [channeler.transcade :as transcade]
            [channeler.async-dl :as async-dl]
            [clojure.core.async :as async]))

(defn ^:private image-url
  [board id ext]
  (format "https://i.4cdn.org/%s/%d%s" board id ext))

(defn ^:private has-image
  [post-json]
  (and (contains? post-json "tim") (contains? post-json "ext")))

(defn ^:private pre-dl?
  "Return true iff post has an image and image is yet to be downloaded"
  [post]
  (and (has-image post)
       (not (contains? post ::dl-chan))))

(defn ^:private init-dl
  [{dir :dir dl-chan :dl-chan} {board :channeler.chan-th/board} post]
  (let [web-filename (clojure.string/join (list (post "tim") (post "ext")))
        url (image-url board (post "tim") (post "ext"))
        local (clojure.java.io/file dir web-filename)
        resp-chan (async/chan)]
    (async/>!! dl-chan (async-dl/dl-request url local resp-chan))
    (-> post
        (transcade/push-awaits resp-chan)
        (assoc ::dl-chan resp-chan))))

(defn ^:private dl-done?
  "Return true iff post image has been downloaded"
  [{dl-chan ::dl-chan :as post}]
  (contains? (post ::transcade/await-results) dl-chan))

(defn ^:private finish-dl
  [_ _ {resp-chan ::dl-chan :as post}]
  (let [[[status _ local] new-post] (transcade/pop-awaits-result post resp-chan)]
    (if (= status ::async-dl/done)
      ;; async-dl tells us it was a success
      (assoc new-post ::local-image-path (.getAbsolutePath local))
      ;; async-dl tells us something else (TODO)
      new-post)))

(defrecord ImageDownload [dir dl-chan]
  transcade/Transformer
  (applicable? [_ _ post] (or (pre-dl? post) (dl-done? post)))
  (transform [this ctx post]
    (if (pre-dl? post)
      (init-dl this ctx post)
      (finish-dl this ctx post))))

(defn plugin-main
  [state _]  ; ignore plug conf for now
  (let [post-transform (->ImageDownload (state :channeler.state/dir)
                                        (state :channeler.state/async-dl-chan))]
    (plugin/register-post-transform post-transform)))