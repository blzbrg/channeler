(ns channeler.config-reload-test
  (:require [clojure.test :as test]
            [clojure.java.io :as io]
            [channeler.thread-manager]
            [channeler.test-lib :as test-lib]
            [clojure.data.json :as json]
            [channeler.config-reload :as config-reload]))

(defn write-json
  [file json]
  (with-open [w (io/writer file)]
    (json/write json w :escape-slash false :escape-js-separators false :escape-unicode false)))

(defn generic-mock
  [call-atom name & args]
  (swap! call-atom conj (cons name args)))

(defn seq-matches?
  [pattern item]
  (loop [[pattern-piece & pattern-rest] pattern
         [item-piece & item-rest] item]
    (cond
      ;; Pattern is done
      (and (nil? pattern-piece) (nil? pattern-rest))
      true
      ;; Don't care or match in this piece
      (or (= :dontcare pattern-piece) (= pattern-piece item-piece))
      (recur pattern-rest item-rest)
      ;; Mismatch
      :else
      false)))

(defn query
  [call-seq pattern]
  (filter #(seq-matches? pattern %) call-seq))

(defn thread-added?
  [call-seq target-board target-thread]
  (not-empty
   (query call-seq ['channeler.thread-manager/add-thread! :dontcare target-board target-thread])))

(test/deftest watch-and-handle-test
  (let [dir (test-lib/tmp-dir)
        context {:conf {"dir" (.getAbsolutePath dir)
                        "thread-conf-dir" (.getAbsolutePath dir)}}
        call-atom (atom (list))]
    (with-redefs [channeler.thread-manager/add-thread!
                  (partial generic-mock call-atom 'channeler.thread-manager/add-thread!)
                  channeler.thread-manager/thread-present?
                  (fn [board thread] (thread-added? @call-atom board thread))]
      (let [conf-file (io/file dir "conf1.json")
            watch-thread (config-reload/init context)]
        ;; TODO: test incomplete config and non-file...how do we do this in a non-racey manner?
        (test/testing "Write complete config - this should cause an add"
          (write-json conf-file {"board" "b" "thread" 1 "dir" (.getAbsolutePath dir)})
          (test/is (= true (test-lib/wait-for #(not-empty @call-atom) 2000)))
          (test/is (= 1 (count (query @call-atom ['channeler.thread-manager/add-thread!
                                                  :dontcare "b" 1])))))))))
