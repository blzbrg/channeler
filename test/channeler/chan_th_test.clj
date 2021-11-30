(ns channeler.chan-th-test
  (:require [channeler.chan-th :as chan-th]
            [channeler.transcade :as transcade]
            [channeler.service :as service]
            [channeler.image-download :as image-download]
            [clojure.test :as test]
            [clojure.java.io :as io]
            [clojure.data.json :as json]))

;; Note that verify-dir-usable and verify-potential-thread are tested in thread-manager-test

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
              ;; TODO: the tim and time vals are actually not correct
              570370
              {"no" 570370,
               "now" "12/31/18(Mon)17:14:00"
               "name" "Anonymous"
               "com" "second post body"
               "tim" 1546294889019
               "time" 1546294889
               "resto" 570368
               "capcode" "mod"}
              570371
              {"no" 570371
               "now" "12/31/18(Mon)17:15:00"
               "name" "Anonymous"
               "com" "third post body"
               "filename" "third_post_filename"
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
               "capcode" "mod"}))

(def sample-post-map-first-two
  ;; Note that the OP changes when the rest of the thread changes!
  (-> sample-post-map
      (dissoc 570371)
      (update-in [10 "replies"] dec)
      (update-in [10 "images"] dec)))

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

(defn mock-th
  [posts]
  (clojure.data.json/write-str {"posts" (vals posts)}))

(defn mock-http-response
  [code body-str]
  (let [always-true-header-filter (reify java.util.function.BiPredicate
                                    (test [_ _ _] true))]
    (reify
      java.net.http.HttpResponse
      (body [_] body-str)
      (headers [_]
        (java.net.http.HttpHeaders/of {"Last-Modified" ["Mon, 1 Jan 2000 10:10:10 GMT"]}
                                      always-true-header-filter))
      (previousResponse [_] (java.util.Optional/empty))
      (request [_] (throw (RuntimeException. "unsupported on mock response")))
      (sslSession [_] (java.util.Optional/empty))
      (statusCode [_] code)
      (uri [_] "")
      (version [_] java.net.http.HttpClient$Version/HTTP_1_1))))

(defn mock-resp
  [posts]
  {:channeler.limited-downloader/http-response (mock-http-response 200 (mock-th posts))})

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
  ;; Mock out expor so it is a nop - we do not want the test to write to the fs
  (with-redefs [chan-th/export-watch (fn [& _] nil)]
    (let [request-log-ref (atom [])
          ctx (test-ctx request-log-ref)
          agt (chan-th/create ctx ["a" 1])]
      (await-for 10000 agt)
      (test/is (= (list expected-th-req) (map relevant-req-keys @request-log-ref))))))

(test/deftest initial-integrate-test
  (let [request-log-ref (atom [])
        ctx (test-ctx request-log-ref)
        agt (agent {::chan-th/board "a" ::chan-th/id 1 ::chan-th/conf (:conf ctx)})
        original-agt-val @agt] ; original val to simulate watch
    (chan-th/dispatch-thread-integrate agt (:state ctx) (mock-resp sample-post-map))
    (await-for 10000 agt)
    (chan-th/request-watch ctx ::chan-th/request-watch agt original-agt-val @agt)
    (await-for 10000 agt)
    ;; Test that the images and refresh were requested
    ;;
    ;; TODO: test the refresh delay?
    (test/is (= (set ["https://i.4cdn.org/a/1546293948883.png"
                      "https://i.4cdn.org/a/1546294496751.png"
                      "https://a.4cdn.org/a/thread/1.json"])
                (set (map :channeler.limited-downloader/download-url @request-log-ref))))))

(test/deftest refresh-integrate-new-image-test
  (let [request-log-ref (atom [])
        ctx (test-ctx request-log-ref)
        initial-th (-> {::chan-th/board "a" ::chan-th/id 1 ::chan-th/conf (:conf ctx)}
                       (assoc "posts" sample-post-map-first-two))
        agt (agent initial-th)]
    (chan-th/dispatch-thread-integrate agt (:state ctx) (mock-resp sample-post-map))
    (await-for 1000 agt)
    (chan-th/request-watch ctx ::chan-th/request-watch agt initial-th @agt)
    (await-for 10000 agt)
    ;; Only the three posts should be present now
    (test/is (= (keys sample-post-map) (keys (get @agt "posts"))))
    ;; The new image and the next refresh
    (test/is (= (set ["https://i.4cdn.org/a/1546294496751.png"
                      "https://a.4cdn.org/a/thread/1.json"])
                (set (map :channeler.limited-downloader/download-url @request-log-ref))))))

(test/deftest first-integ-404-test
  (let [request-log-ref (atom [])
        ctx (test-ctx request-log-ref)
        agt (agent {::chan-th/board "a" ::chan-th/id 1 ::chan-th/conf (:conf ctx)})]
    (chan-th/dispatch-thread-integrate agt (:state ctx)
                                       {:channeler.limited-downloader/http-response
                                        (mock-http-response 404 "")})
    (await-for 10000 agt)
    (test/is (= 404 (::chan-th/completed @agt)))
    (test/is (= (list) (sequence @request-log-ref)))))

(test/deftest cache-headers-test
  ;; Test that Last-Modified header is stored properly, and If-Modified-Since is sent properly. Then
  ;; test that 304 is handled correctly.
  (let [request-log-ref (atom [])
        ctx (test-ctx request-log-ref)
        headers-req {:channeler.limited-downloader/headers
                     {"If-Modified-Since" "Mon, 1 Jan 2000 10:10:10 GMT"}}
        agt (agent {::chan-th/board "a" ::chan-th/id 1 ::chan-th/conf (:conf ctx)})
        original-agt-val @agt] ; store to simulate request watch
    ;; Initial 200 response and watch
    (chan-th/dispatch-thread-integrate agt (:state ctx) (mock-resp sample-post-map))
    (await-for 10000 agt)
    (chan-th/request-watch ctx ::chan-th/request-watch agt original-agt-val @agt)
    (await-for 10000 agt)
    ;; Test that the Last-Modified is stored
    (test/is (= "Mon, 1 Jan 2000 10:10:10 GMT" (::chan-th/last-modified @agt)))
    ;; Test that the request for the refresh includes the If-Modified-Since
    (let [[refresh-req] (filter (fn [req] (= (:channeler.limited-downloader/download-url req)
                                             "https://a.4cdn.org/a/thread/1.json"))
                                    @request-log-ref)]
      (test/is (= headers-req (select-keys refresh-req [:channeler.limited-downloader/headers]))))
    ;; Clear requests
    (reset! request-log-ref [])
    ;; Second response and watch, this time a 304
    (let [int-agt-val @agt] ; intermediate value to simulate the next watch
      (chan-th/dispatch-thread-integrate agt (:state ctx)
                                         {:channeler.limited-downloader/http-response
                                          (mock-http-response 304 "")})
      (await-for 10000 agt)
      (chan-th/request-watch ctx ::chan-th/request-watch agt int-agt-val @agt)
      (await-for 10000 agt)
      ;; Test that the counter used to drive the exponential backoff has incremented to 1
      (test/is (= 1 (::chan-th/sequential-unmodified-fetches @agt)))
      ;; Test that the only request is the refresh
      ;; TODO: test that the time is larger than the base refresh time
      (test/is (= (list headers-req) (map #(select-keys % [:channeler.limited-downloader/headers])
                                          @request-log-ref))))))

(test/deftest trim-for-export-test
  (test/is (= {"a" 1 "b" {"c" (sorted-map 1 :a 2 :b)}}
              (chan-th/trim-for-export {"a" 1 :rem1 "rem1" ::rem2 "rem2"
                                        "b" {"c" (sorted-map 1 :a 2 :b) :rem3 "rem3"}}))))

(test/deftest export-thread-test
  (let [ctx (test-ctx (atom []))
        agt (agent {::chan-th/board "a" ::chan-th/id 1 ::chan-th/conf (:conf ctx)})
        original-agt-val @agt ; store to simulate watch
        string-writer (java.io.StringWriter.)]
    ;; Initial 200 response and export watch
    (chan-th/dispatch-thread-integrate agt (:state ctx) (mock-resp sample-post-map))
    (await-for 10000 agt)
    (with-redefs [chan-th/export-file (fn [_ _] string-writer)]
      (chan-th/export-watch ctx ::chan-th/export-watch agt original-agt-val @agt)
      (await-for 10000 agt))
    (let [written (-> string-writer
                      (.getBuffer)
                      (str))]
      (test/is (= (apply json/write-str {"posts" sample-post-map} chan-th/write-options)
                  written)))))
