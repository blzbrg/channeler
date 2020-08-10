(ns channeler.config-test
  (:require [clojure.test :refer :all]
            [channeler.config :as config]))

(def get-bottom-layer
  {:a 1 :b 2})

;; (defn assert-get-expected
;;   [keys-and-expecteds path-prefix config]
;;   (doseq [[final-key expected-val] keys-and-expecteds]
;;     (let [path (concat path-prefix final-key)]
;;       (is (apply config/fallback-get config path) expected-val))))

(deftest get-simple-path-test
  (let [bottom {:a 1 :b 2}]
    (doseq [conf [get-bottom-layer
                  [get-bottom-layer]
                  [(atom get-bottom-layer)]
                  [get-bottom-layer {}]
                  [{} get-bottom-layer]]]
      (is (= 1 (config/conf-get conf :a)))
      (is (nil? (config/conf-get conf :c))))))

(deftest get-avoid-explicit-nil-test
  "Should treat explicit nils as a missing entry. This is not actually an important behavior, but
  test it to make sure it is consistent."
  (let [first {:a nil} second {:a 1}]
    (doseq [conf [[first second]
                  [(atom first) second]
                  [first (atom second)]]]
      (is (= 1 (config/conf-get conf :a))))))