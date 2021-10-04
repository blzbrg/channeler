(ns channeler.image-download
  (:require [channeler.plugin :as plugin]
            [channeler.transcade :as transcade]
            [channeler.async-dl :as async-dl]
            [channeler.config :refer [conf-get]]
            [clojure.core.async :as async]
            [channeler.chan-th :as chan-th]))

(defn ^:private image-url
  [board id ext]
  (format "https://i.4cdn.org/%s/%d%s" board id ext))

(defn ^:private has-image
  [post-json]
  (and (contains? post-json "tim") (contains? post-json "ext")))

(defn requested?
  [post]
  (or (get-in post [:channeler.request/responses ::image-download])
      (get-in post [:channeler.request/requests ::image-download])))

(defn local-path
  [conf post]
  (let [filename (clojure.string/join [(post "tim") (post "ext")])]
    ;; stupid dance to call varargs method
    (.toString (java.nio.file.Path/of (conf-get conf "dir") (into-array [filename])))))

(defn req-dl
  [{board ::chan-th/board conf ::chan-th/conf state ::chan-th/state} post]
  (let [req {;; remote
             :channeler.limited-downloader/download-url
             (image-url board (post "tim") (post "ext"))
             ;; local
             :channeler.limited-downloader/download-target
             (local-path conf post)
             ;; target service
             :channeler.service/service-key
             :channeler.limited-downloader/rate-limited-downloader}]
    (assoc-in post [:channeler.request/requests ::image-download] req)))

(defrecord RequestImageDownload []
  transcade/Transformer
  (applicable? [_ _ post] (and (has-image post) (not (requested? post))))
  (transform [_ ctx post] (req-dl ctx post)))

(defn plugin-main
  [_ plug-conf]  ; ignore context for now
  (let [post-transform (->RequestImageDownload)]
    (plugin/register-post-transform post-transform)))
