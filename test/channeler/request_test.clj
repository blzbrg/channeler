(ns channeler.request-test
  (:require [channeler.request :as request]
            [clojure.test :as test]))

(def req-ctx
  {:a 1
   :b {:c {::request/requests {:a-req {}
                               "b-req" {}}}}})

(test/deftest request-test
  (let [a-req (request/contexified-req req-ctx [:b :c] :a-req)
        b-req (request/contexified-req req-ctx [:b :c] "b-req")]
    (test/testing "Explicitly test contexify output"
      (test/is (= {::request/path-in-context (seq [:b :c]) ::request/req-key :a-req}
                  a-req))
      (test/is (= {::request/path-in-context (seq [:b :c]) ::request/req-key "b-req"}
                  b-req)))
    (test/testing "Test req path"
      (test/is (= (seq [:b :c ::request/requests :a-req]) (request/req-path a-req)))
      (test/is (= (seq [:b :c ::request/requests "b-req"]) (request/req-path b-req))))
    (test/testing "Remove request"
      (test/is (= {:a 1 :b {:c {::request/requests {"b-req" {}}}}}
                  (request/remove-req-from-context req-ctx a-req)))
      (test/is (= {:a 1 :b {:c {}}}
                  (request/remove-requests-from-context req-ctx [a-req b-req]))))))

(def resp-ctx
  {:a 1
   :b {:c {}}})

(def a-resp {::request/path-in-context [:b :c] ::request/req-key :a-req})
(def b-resp {::request/path-in-context [:b :c] ::request/req-key "b-req"})

(test/deftest resp-path-test
  (test/is (= (seq [:b :c ::request/responses :a-req]) (request/resp-path a-resp)))
  (test/is (= (seq [:b :c ::request/responses "b-req"]) (request/resp-path b-resp))))

(test/deftest resp-integrate-test
  (test/is (= {:a 1 :b {:c {::request/responses {:a-req a-resp}}}}
              (request/integrate-response resp-ctx a-resp)))
  (test/is (= {:a 1 :b {:c {::request/responses {:a-req a-resp "b-req" b-resp}}}}
              (request/integrate-responses resp-ctx [a-resp b-resp]))))
