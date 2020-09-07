(ns channeler.thread-manager-test
  (:require [clojure.test :as test]
            [channeler.config :refer [conf-get]]
            [channeler.thread-manager :refer :all]))

(test/deftest context-for-thread-test
  (let [base {:conf {"dir" "/a"} :state {}}]
    (test/is (= "/a" (conf-get (:conf (context-for-thread base {})) "dir")))
    (test/is (= "/b" (conf-get (:conf (context-for-thread base {"dir" "/b"})) "dir")))))
