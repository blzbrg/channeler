(ns channeler.map-tools)

(defn find-matching-paths
  "Given a map of maps, traverse depth-first looking for the largest subtrees that satisfy `pred`. If
  a subtree matches, no traversal is done into that subtree."
  [d pred]
  (loop [paths (list)
         ;; queue of [reverse-path-to-d d]
         todos (-> clojure.lang.PersistentQueue/EMPTY
                   (conj [(list) d]))]
    (if-let [[rpath-to-current current] (peek todos)]
      (let [popped-todos (pop todos)]
        (if (map? current)
          (if (pred current)
            ;; `current` matches `pred`, add its' path and continue with walk. Do not traverse into
            ;; subtree under `current`
            (recur (cons rpath-to-current paths) popped-todos)
            ;; `current` does not match `pred`, add children to todos
            (let [path-child-pairs (map (fn [[k child]] [(cons k rpath-to-current) child]) current)
                  new-todos (into popped-todos path-child-pairs)]
              (recur paths new-todos)))
          ;; current is something we cannot descend into, go on with todos
          (recur paths popped-todos)))
      ;; base case: nothing in todos. Change path back to forward path
      (map reverse paths))))