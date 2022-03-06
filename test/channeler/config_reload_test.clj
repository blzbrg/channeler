(ns channeler.config-reload-test
  (:require [clojure.test :as test]
            [clojure.java.io :as io]
            [channeler.thread-manager]
            [channeler.config :as config]
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
   (query call-seq ['channeler.thread-manager/add-thread! :dontcare target-board
                    target-thread])))


(test/deftest watch-and-handle-test
  (let [dir (test-lib/tmp-dir)
        context {:conf (config/base {"dir" (.getAbsolutePath dir)
                                     "thread-conf-dir" (.getAbsolutePath dir)
                                     "board" "b"})}
        call-atom (atom (list))]
    (with-redefs [channeler.thread-manager/add-thread!
                  (partial generic-mock call-atom 'channeler.thread-manager/add-thread!)
                  channeler.thread-manager/reconfigure-thread!
                  (partial generic-mock call-atom 'channeler.thread-manager/reconfigure-thread!)
                  channeler.thread-manager/thread-present?
                  (fn [board thread] (thread-added? @call-atom board thread))]
      (let [conf-file (io/file dir "conf1.json")
            watch-thread (config-reload/init context)]
        ;; TODO: test incomplete config and non-file...how do we do this in a non-racey manner?
        (test/testing "Write config - this should cause an add"
          (write-json conf-file {"thread" 1})
          (test/is (= true (test-lib/wait-for #(not-empty @call-atom) 2000)))
          (test/is (= 1 (count (query @call-atom ['channeler.thread-manager/add-thread!
                                                  :dontcare "b" 1]))))
          ;; Every file creation comes with a CREATE and MODIFY event. We don't need both, but
          ;; empirically this is how they show up on linux, so encode it in the test.
          (test/is (= 1 (count (query @call-atom
                                      ['channeler.thread-manager/reconfigure-thread! "b" 1])))))
        (test/testing "Reconfigure thread - change dir and check for a reconfigure call"
          ;; This also tests that the dir from the base config can be overwritten by the
          ;; higher-level config.
          (test-lib/wait-for #(empty? @call-atom) 2000)
          (let [new-path (.getAbsolutePath (io/file dir "newdir"))]
            (write-json conf-file {"thread" 1 "dir" new-path})
            ;; Wait for latest call to be the reconfig which applies the new directory
            (test/is (test-lib/wait-for
                      (fn [] (let [[_ _ _ new-conf] (first @call-atom)]
                               (= new-path (config/conf-get new-conf "dir")))) 2000))))))))
