(ns channeler.transcade-test
  (:require [clojure.test :refer :all]
            [channeler.transcade :as transcade]))

(def a-to-10
  {:applicable-fn (fn [state]
                    (< (state :a) 10))
   :transform-fn (fn [state]
                   (update state :a inc))})

(defn make-min
  [& transformer-seq]
  {:transformers transformer-seq})

(deftest a-to-10-step-test
  (is {:a 2}
      (transcade/transform-step (make-min a-to-10) {:a 1})))

(deftest a-to-10-test
  (is {:a 9}
      (transcade/inline-loop (make-min a-to-10) {:a 1})))
