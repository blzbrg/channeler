(ns channeler.transcade-test
  (:require [clojure.test :refer :all]
            [channeler.transcade :as transcade]))

(defrecord ATo10 []
  transcade/Transformer
  (applicable? [this _ state] (< (state :a) 10))
  (transform [this _ state] (update state :a inc)))

(defn make-min
  [& transformer-seq]
  {:transformers transformer-seq})

(def a-to-10
  (make-min (ATo10.)))

(deftest a-to-10-step-test
  (is {:a 2}
      (transcade/transform-step a-to-10 nil {:a 1})))

(deftest a-to-10-test
  (is {:a 9}
      (transcade/inline-loop a-to-10 nil {:a 1})))
