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
    (doseq [conf [(list [:bot get-bottom-layer])
                  (list [:bot (atom get-bottom-layer)])
                  (list [:top get-bottom-layer] [:bot {}])
                  (list [:top {}] [:bot get-bottom-layer])]]
      (is (= 1 (config/conf-get conf :a)))
      (is (nil? (config/conf-get conf :c))))))

(deftest get-two-step-path-test
  (let [bot {:a {:b :c}}]
    (doseq [conf [(config/base bot)
                  (cons [:a {}] (config/base bot))]]
      (is (= :c (config/conf-get conf :a :b))))))

(deftest get-avoid-explicit-nil-test
  "Should treat explicit nils as a missing entry. This is not actually an important behavior, but
  test it to make sure it is consistent."
  (let [first {:a nil} second {:a 1}]
    (doseq [conf [(list [:top first] [:bot second])
                  (list [:top (atom first)] [:bot second])
                  (list [:top first] [:bot (atom second)])]]
      (is (= 1 (config/conf-get conf :a))))))
