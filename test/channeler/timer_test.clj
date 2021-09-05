(ns channeler.timer-test
  (:require [channeler.timer :as timer]
            [clojure.test :as test]))

(def sample-four
  (sorted-map 1 (list :a)
              2 (list :b :c)
              3 (list :d)
              4 (list :e :f)))

(test/deftest remove-expired-all-test
  (test/is (= (sorted-map 3 (list :d) 4 (list :e :f))
              (timer/remove-expired-all sample-four 2))))

(defn expired-and-sched
  [sched-ref & args]
  (let [expired (apply timer/expire-items sched-ref args)]
    [expired @sched-ref]))

(test/deftest expire-all-with-atom-test
  (test/is (= [(seq [:a :b :c]) (sorted-map 3 (list :d) 4 (list :e :f))]
              (expired-and-sched (atom sample-four) 2)))
  (test/is (= [(seq [:a :b :c :d]) (sorted-map 4 (list :e :f))]
              (expired-and-sched (atom sample-four) 3))))

(def sample-two
  (sorted-map 1 (list :a) 2 (list :b)))

(test/deftest schedule-test
  (test/is (= (sorted-map 1 (list :a) 2 (list :new :b))
              (timer/schedule (atom sample-two) 2 :new)))
  (test/is (= (sorted-map 1 (list :a) 2 (list :b) 3 (list :new))
              (timer/schedule (atom sample-two) 3 :new))))
