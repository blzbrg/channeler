(ns channeler.remote-control-test
  (:require [clojure.test :as test]
            [channeler.remote-control :refer :all]))

(defn str->reader
  [input]
  (->> input
       (new java.io.StringReader)
       (new java.io.BufferedReader)))

(test/deftest line-loop-test
  (test/is (= ["a" "b" "c"] (line-loop (str->reader "a\nb\nc\n\n"))))
  (test/is (= ["a" "b"] (line-loop (str->reader "a\nb\n\n"))))
  (test/is (= ["a"] (line-loop (str->reader "a\n\n")))))