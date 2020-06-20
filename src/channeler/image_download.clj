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
  [remote-path local]
  (with-open [in (clojure.java.io/input-stream remote-path)
              out (clojure.java.io/output-stream local)]
    (clojure.java.io/copy in out)))

(defrecord ImageDownload [dir]
  transcade/Transformer
  (applicable? [_ _ post] (and (not (contains? post ::local-image-path))
                               (has-image post)))
  (transform [{dir :dir} {board :channeler.chan-th/board} post]
    (let [web-filename (clojure.string/join (list (post "tim") (post "ext")))
          url (image-url board (post "tim") (post "ext"))
          local (clojure.java.io/file dir web-filename)]
      (download url local)
      (assoc post ::local-image-path (.getAbsolutePath local)))))

(defn plugin-main
  [state _]  ; ignore plug conf for now
  (let [post-transform (->ImageDownload (state :channeler.state/dir))]
    (plugin/register-post-transform post-transform)))