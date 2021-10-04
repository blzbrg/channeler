(ns channeler.chan-th-test
  (:require [channeler.chan-th :as chan-th]
            [channeler.transcade :as transcade]
            [clojure.test :as test]
            [clojure.java.io :as io]
            [clojure.data.json :as json]))

(def sample-data
  (-> "three_post_sample.json"
      (io/resource)
      (io/reader)
      (json/read)))

(def sample-post-map
  (sorted-map 10
              {"no" 10
               "sticky" 1
               "closed" 1
               "now" "12/31/18(Mon)17:05:48"
               "name" "Anonymous"
               "sub" "post title"
               "com" "OP post contents"
               "filename" "OP_filename"
               "ext" ".png"
               "w" 530
               "h" 449
               "tn_w" 250
               "tn_h" 211
               "tim" 1546293948883
               "time" 1546293948
               "md5" "uZUeZeB14FVR+Mc2ScHvVA=="
               "fsize" 516657
               "resto" 0
               "capcode" "mod"
               "semantic_url" "post-title"
               "replies" 2
               "images" 2
               "unique_ips" 1}
              570370
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
               "capcode" "mod"}
              570371
              {"no" 570371,
               "now" "12/31/18(Mon)17:21:29"
               "name" "Anonymous"
               "com" "third post body"
               "tim" 1546294889019
               "time" 1546294889
               "resto" 570368
               "capcode" "mod"}))

(test/deftest post-seq->map-test
  (test/is (= (chan-th/post-seq->map (sample-data "posts")) sample-post-map)))

(test/deftest find-transcades-test
  (let [marked-post-m (-> sample-data
                          (chan-th/mapify-thread)
                          ;; mark with a bogus transcade (just has to be truthy)
                          (update-in ["posts" 570371] (partial transcade/mark-needed true)))]
    (test/is (= (list (list "posts" 570371)) (chan-th/find-transcades marked-post-m)))))
