(ns channeler.timer
  "Framework for keeping a collection of opaque events and their expiry times. Users should implement
  a loop which periodically calls `expire-items` with the current time. The return value is a
  sequence of newly expired events. `sleep-interval-ref` is intended for use in `Thread/sleep`.

  An example loop could look like:
  (loop []
    (expired-handler (expire-items sched-ref (System/nanoTime)))
    (Thread/sleep @sleep-interval-ref)
    (recur))"
  (:require [clojure.data :as data]))

;; === Expiry ===

(defn remove-expired-all
  [schedule now]
  (apply dissoc schedule (take-while #(<= % now) (keys schedule))))

(defn seq-removed-items
  [old new]
  (loop [o old
         n new
         acc (list)]
    (if (not-empty o)
      ;; note that if n is already empty this is ok - first and next will return nil
      (if (= (first o) (first n))
        (recur (next o) (next n) acc)
        (recur (next o) n (cons (first o) acc)))
      (reverse acc))))

(defn items-removed
  [old new]
  (->> old
       (map (fn [[old-key old-items]] (seq-removed-items old-items (get new old-key))))
       (flatten)))

(defn expire-items
  [schedule-ref now-nanos]
  (apply items-removed (swap-vals! schedule-ref remove-expired-all now-nanos)))

;; === Loop helpers ===

(def sleep-interval-ref
  "Duration, in miliseconds, to sleep between `expire-items` calls."
  (atom 500))
