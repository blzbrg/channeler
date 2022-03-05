(ns channeler.image-download-test
  (:require [clojure.test :as test]
            [channeler.image-download :as image-download]
            [channeler.transcade :as transcade]))

(def sample-post
  {"no" 570370
   "now" "12/31/18(Mon)17:14:56"
   "name" "Anonymous"
   "com" "second post body"
   "filename" "second_post_filename"
   "ext" ".png"
   "w" 318
   "h" 704
   "tn_w" 56
   "tn_h" 125
   "tim" 1546294496751
   "time" 1546294496
   "md5" "0EqXBb4gGIyzQiaApMdFAA=="
   "fsize" 285358
   "resto" 570368
   "capcode" "mod"})

(def sample-th-ctx
  {:channeler.chan-th/board "po"
   :channeler.chan-th/conf (channeler.config/base {"dir" "/a/b"})
   :channeler.chan-th/state {}})

(test/deftest applicable-and-transform-test
  (let [trans (image-download/->RequestImageDownload)
        transformed (transcade/transform trans sample-th-ctx sample-post)]
    (test/is (transcade/applicable? trans sample-th-ctx sample-post))
    (test/is (= {:channeler.limited-downloader/download-url "https://i.4cdn.org/po/1546294496751.png"
                 :channeler.limited-downloader/download-target "/a/b/1546294496751.png"
                 :channeler.service/service-key
                 :channeler.limited-downloader/rate-limited-downloader}
                (get-in transformed [:channeler.request/requests ::image-download/image-download])))
    (test/is (not (transcade/applicable? trans sample-th-ctx transformed)))))
