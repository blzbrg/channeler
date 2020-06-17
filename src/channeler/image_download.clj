(ns channeler.image-download
  (:require [channeler.plugin :as plugin]
            [channeler.transcade :as transcade]))

(defn ^:private image-url
  [board id ext]
  (format "https://i.4cdn.org/%s/%d%s" board id ext))

(defn ^:private has-image
  [post-json]
  (and (contains? post-json "tim") (contains? post-json "ext")))

(defn ^:private download
  [remote-path local-path]
  (with-open [in (clojure.java.io/input-stream remote-path)
              out (clojure.java.io/output-stream local-path)]
    (clojure.java.io/copy in out)))

(defrecord ImageDownload [dir]
  transcade/Transformer
  (applicable? [_ _ state] (and (not (contains? state ::local-image-path))
                                (has-image state)))
  (transform [{dir :dir} {board :channeler.chan-th/board} state]
    (let [web-filename (clojure.string/join (list (state "tim") (state "ext")))
          url (image-url board (state "tim") (state "ext"))
          local-path (clojure.string/join (list dir web-filename))]
      (download url local-path)
      (assoc state ::local-image-path local-path))))

(defn plugin-main
  [conf]
  (let [post-transform (->ImageDownload (conf :channeler.state/dir))]
    (plugin/register-post-transform post-transform)))