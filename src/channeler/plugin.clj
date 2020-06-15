(ns channeler.plugin)

(def reg-state (atom {}))

(defn ^:private add-to-transcade
  [which-transcade transform reg]
  (let [path [::transcades which-transcade :transformers]
        current-transforms (or (get-in reg path) ())]
    (assoc-in reg path (conj current-transforms transform))))

(defn register-post-transform
  [transform]
  (swap! reg-state (partial add-to-transcade :post-transcade transform)))