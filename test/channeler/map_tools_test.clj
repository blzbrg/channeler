(ns channeler.map-tools-test
  (:require [channeler.map-tools :as map-tools]
            [clojure.test :as test]))

(defn target? [d] (contains? d :target))

(test/deftest find-matching-paths-test
  (let [d {:a 1 :b {:c 2 :target "foo" :d {:target "bar"}} :e {:f {:target "baz"}}}]
    (test/is (= (set [(list :b) (list :e :f)])
                (set (map-tools/find-matching-paths d target?))))))
