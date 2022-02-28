(ns channeler.limited-downloader-test
  (:require [clojure.test :as test]
            [channeler.test-lib :refer [wait-for]]
            [channeler.limited-downloader :as dl]
            [channeler.service :as service]))

(defn make-mock-timer
  [start]
  (let [at (atom start)]
    [at (fn [] @at)]))

(test/deftest basic-expire-test
   (let [asap {::dl/download-url "http://a.b/asap"}
         timed {::dl/download-url "http://a.b/timed" ::dl/time-nano 1}
         asap-ref (atom [asap])
         sched-ref (atom (sorted-map 1 [timed]))]
     (test/is (= asap
                 (second (dl/timer-loop-dequeue-and-selection 0 clojure.lang.PersistentQueue/EMPTY
                                                              sched-ref asap-ref))))
     (test/is (= timed
                 (second (dl/timer-loop-dequeue-and-selection 1 clojure.lang.PersistentQueue/EMPTY
                                                              sched-ref asap-ref))))))

(test/deftest expire-priority-test
  ;; When a timed is eligible to expire and there is an asap in the queue, the timed should expire
  ;; first.
  (let [[mock-time-ref mock-time-fn] (make-mock-timer 0)
        asap {::dl/download-url "http://a.b/asap"}
        timed {::dl/download-url "http://a.b/timed" ::dl/time-nano 0}
        asap-ref (atom [asap])
        sched-ref (atom (sorted-map 0 [timed]))]
    (test/is (= timed
                (second (dl/timer-loop-dequeue-and-selection 0 clojure.lang.PersistentQueue/EMPTY
                                                             sched-ref asap-ref))))
    (test/is (= asap
                (second (dl/timer-loop-dequeue-and-selection 0 clojure.lang.PersistentQueue/EMPTY
                                                             sched-ref asap-ref))))))

(test/deftest service-integration-test
  (let [[mock-time-ref mock-time-fn] (make-mock-timer 0)
        downloaded-reqs (atom [])
        asap {::dl/download-url "http://a.b/asap"}
        timed {::dl/download-url "http://a.b/timed" ::dl/time-nano 1}]
    (with-redefs [dl/download-req! (fn [req] (swap! downloaded-reqs conj req))]
      (let [mock-ctx {:conf [{"network-rate-limit" {"min-sec-between-downloads" 0.1}}]}
            {svc ::dl/rate-limited-downloader} (dl/init-service mock-ctx mock-time-fn)]
        (service/handle-item! svc timed)
        (service/handle-item! svc asap)
        (test/is (wait-for (fn [] (= (set [asap]) (set @downloaded-reqs))) 5000))
        (test/is (= (list timed) (get @(:sched-ref svc) 1)))
        (reset! downloaded-reqs [])
        (reset! mock-time-ref 1)
        (test/is (wait-for (fn [] (set [timed]) (set @downloaded-reqs)) 5000))
        (service/stop svc)))))
