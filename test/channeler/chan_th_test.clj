(ns channeler.chan-th-test
  (:require [channeler.chan-th :as chan-th]
            [channeler.transcade :as transcade]
            [channeler.service :as service]
            [channeler.image-download :as image-download]
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

(defrecord MockService [request-log-ref]
  service/Service
  (handle-item! [_ item] (swap! request-log-ref conj item))
  (stop [_] nil))

(defn test-ctx
  [request-log-ref]
  {:state {:post-transcade {:transformers (list (image-download/->RequestImageDownload))}
           :service-map {:channeler.limited-downloader/rate-limited-downloader
                         (->MockService request-log-ref)}}
   :conf {"dir" "/tmp/channeler" "thread" {"min-sec-between-refresh" 10}}})

(defn relevant-req-keys
  "Relevant keys from a request, eg. leave out anything incomparable like functions."
  [req]
  (select-keys req [:channeler.service/service-key :channeler.limited-downloader/download-url]))

(def expected-th-req
  {:channeler.service/service-key
   :channeler.limited-downloader/rate-limited-downloader
   :channeler.limited-downloader/download-url
   "https://a.4cdn.org/a/thread/1.json"})

(test/deftest create-test
  (let [request-log-ref (atom [])
        ctx (test-ctx request-log-ref)
        agt (chan-th/create ctx ["a" 1])]
    (await-for 10000 agt)
    (test/is (= (list expected-th-req) (map relevant-req-keys @request-log-ref)))))

(test/deftest initial-integrate-test
  (let [request-log-ref (atom [])
        ctx (test-ctx request-log-ref)
        agt (agent {::chan-th/board "a" ::chan-th/id 1 ::chan-th/conf (:conf ctx)})
        original-agt-val @agt] ; original val to simulate watch
    ;; mock thread parsing so that we can use the preparsed sample
    (with-redefs [chan-th/response->thread-structure identity]
      (chan-th/dispatch-thread-integrate agt (:state ctx) {"posts" sample-post-map})
      (await-for 10000 agt)
      (chan-th/request-watch ctx ::chan-th/request-watch agt original-agt-val @agt)
      (await-for 10000 agt)
      ;; Test that the images and refresh were requested
      ;;
      ;; TODO: test the refresh delay?
      (test/is (= (set ["https://i.4cdn.org/a/1546293948883.png"
                        "https://i.4cdn.org/a/1546294496751.png"
                        "https://a.4cdn.org/a/thread/1.json"])
                  (set (map :channeler.limited-downloader/download-url @request-log-ref)))))))

(test/deftest refresh-integrate-new-image-test
  (let [request-log-ref (atom [])
        ctx (test-ctx request-log-ref)
        initial-th (-> {::chan-th/board "a" ::chan-th/id 1 ::chan-th/conf (:conf ctx)}
                       (assoc "posts" (dissoc sample-post-map 570370)))
        agt (agent initial-th)]
    ;; mock thread parsing so that we can use the preparsed sample
    (with-redefs [chan-th/response->thread-structure identity]
      (chan-th/dispatch-thread-integrate agt (:state ctx) {"posts" sample-post-map})
      (await-for 1000 agt)
      (chan-th/request-watch ctx ::chan-th/request-watch agt initial-th @agt)
      (await-for 10000 agt)
      ;; Only the three posts should be present now
      (test/is (= (keys sample-post-map) (keys (get @agt "posts"))))
      ;; The new image and the next refresh
      (test/is (= (set ["https://i.4cdn.org/a/1546294496751.png"
                        "https://a.4cdn.org/a/thread/1.json"])
                  (set (map :channeler.limited-downloader/download-url @request-log-ref)))))))
