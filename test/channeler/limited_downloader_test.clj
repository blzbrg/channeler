(ns channeler.limited-downloader-test
  (:require [clojure.test :as test]
            [channeler.limited-downloader :as dl]
            [channeler.service :as service]))

(defn make-mock-timer
  [start]
  (let [at (atom start)]
    [at (fn [] @at)]))

(defn wait-for
  [cond-fn timeout]
  (if (cond-fn)
    true
    (if (<= timeout 0)
      false
      (do (Thread/sleep 10)
          (recur cond-fn (- timeout 10))))))

(test/deftest expire-asap-and-timed
  (let [[mock-time-ref mock-time-fn] (make-mock-timer 0)
        downloaded-reqs (atom [])
        asap {::dl/download-url "http://a.b/asap"}
        timed {::dl/download-url "http://a.b/timed" ::dl/time-nano 0}]
    (with-redefs [dl/download-req! (fn [req] (swap! downloaded-reqs conj req))]
      (let [mock-ctx {:conf [{"network-rate-limit" {"min-sec-between-downloads" 0.1}}]}
            {svc ::dl/rate-limited-downloader} (dl/init-service mock-ctx mock-time-fn)]
        (service/handle-item! svc timed)
        (service/handle-item! svc asap)
        ;; There is no way to know which request will be dequeued first, because the thread loop is
        ;; running as soon as the service is created. When the time loop delay time is integrated
        ;; with the time source this can be better controlled and a better test can be written.
        (test/is (wait-for (fn [] (= (set [timed asap]) (set @downloaded-reqs))) 10000))))))
