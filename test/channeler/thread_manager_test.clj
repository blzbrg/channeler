(ns channeler.thread-manager-test
  (:require [clojure.test :as test]
            [channeler.test-lib :refer [tmp-dir dir-mock-ctx]]
            [channeler.config :as config :refer [conf-get]]
            [channeler.thread-manager :refer :all]
            [clojure.java.io :as io]
            [clojure.tools.logging.test :as log-test]))

(test/deftest context-for-thread-test
  (let [base {:conf (config/base {"dir" "/a"}) :state {}}]
    (test/is (= "/a" (conf-get (:conf (context-for-thread base {})) "dir")))
    (test/is (= "/b" (conf-get (:conf (context-for-thread base {"dir" "/b"})) "dir")))))

(defn verify-only-log
  "Return `true` iff the log in the format given by `log-test/the-log` contains exacty one entry for
  which `log-test/matches?` returns `true`."
  ([log ns level message] (verify-only-log log ns level nil message))
  ([log ns level throwable message]
   (let [[fst & rest] log]
     (if (empty? rest)
       (log-test/matches? fst ns level throwable message)
       false))))

(test/deftest spawn-thread-verification-test
  (let [dir (tmp-dir)
        bogus-path (.getAbsolutePath (io/file dir "bogus"))
        file (io/file dir "file")
        file-path (.getAbsolutePath file)
        ro-dir (io/file dir "d")
        ro-dir-path (.getAbsolutePath ro-dir)
        creates (atom (list))]
    (with-redefs [channeler.chan-th/create
                  (fn [_ arg] (swap! creates conj arg))]
      ;; Can keep reusing ["abc" 1] because only the final one results in an actual create. This is
      ;; order dependent because if verification passes, a ref will be stored by spawn-thread!

      ;;Path which doesn't exist cannot be used
      (log-test/with-log
        (test/testing "A nonexistant path"
          (spawn-thread! (dir-mock-ctx bogus-path) "abc" 1)
          (test/is (verify-only-log (log-test/the-log) 'channeler.thread-manager :error
                                    (str "Ignoring abc thread 1 because " bogus-path
                                         " does not exist")))
          (test/is (= @creates (list)) "does not result in a creation call")))
      ;; File cannot be used
      (log-test/with-log
        (test/testing "A file"
          (spit file "file rather than dir")
          (.deleteOnExit file) ; since we create, we must mark for delete
          (spawn-thread! (dir-mock-ctx file-path) "abc" 1)
          (test/is (verify-only-log (log-test/the-log) 'channeler.thread-manager :error
                                    (str "Ignoring abc thread 1 because " file-path
                                         " is not a directory"))
                   "cannot be used as dir")
          (test/is (= @creates (list)) "does not result in a creation call")))
      ;; Read-only dir cannot be used
      (log-test/with-log
        (test/testing "A read-only dir"
          ;; Setup
          (test/is (= true (.mkdir ro-dir))) ; create
          (.deleteOnExit ro-dir) ; since we create, we must mark for delete
          (test/is (= true (.setWritable ro-dir false false)))
          ;; Test
          (spawn-thread! (dir-mock-ctx ro-dir-path) "abc" 1)
          (test/is (verify-only-log (log-test/the-log) 'channeler.thread-manager :error
                                    (str "Ignoring abc thread 1 because " ro-dir-path
                                         " is not writeable"))
                   "cannot be used as dir")
          (test/is (= @creates (list)) "does not result in a creation call")))
      ;; Subdir of read-only dir cannot be created
      (log-test/with-log
        (test/testing "Un-createable subdir (create-missing-parents)"
          (let [ro-subdir-path (.getAbsolutePath (io/file ro-dir "uncreatable-subdir"))]
            (spawn-thread! {:conf (config/base {"dir" ro-subdir-path
                                                "create-missing-parents" true})}
                           "abc" 1)
            (test/is (verify-only-log (log-test/the-log) 'channeler.thread-manager :error
                                      #"is not writeable")
                     "cannot be used as dir")
            (test/is (= @creates (list)) "does not result in a creation call"))))
      ;; Otherwise, dir can be used
      (log-test/with-log
        (test/testing "Normal dir"
          (spawn-thread! (dir-mock-ctx (.getAbsolutePath dir)) "abc" 1)
          (test/is (empty? (log-test/the-log)) "nothing is logged")
          (test/is (= @creates (list ["abc" 1])) "results in a create"))))))
