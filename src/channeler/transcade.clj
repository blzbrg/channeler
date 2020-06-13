(ns channeler.transcade)

(defprotocol Transformer
  "A single transformer, a sequence of these combine to be a transcade"
  (applicable? [this state] "Whether this transformer is applicable to the given state")
  (transform [this state] "Take state and transform it, returning new state."))

(defn ^:private select-applicable
  [transcade state]
  (first (drop-while (fn [trans] (not (applicable? trans state)))
                     (transcade :transformers))))

(defn transform-step
  "A single step of the transform. Either executes one transform and
  returns [:new-state new-state] or executes no transform (because
  none are applicable) and returns [:unchanged state]."
  [transcade state]
  (if-let [trans (select-applicable transcade state)]
    [:new-state (transform trans state)]
    [:unchanged state]))

(defn inline-loop
  "Perform transform-step until transform-step returns [:unchanged
  _] (which indicates it is time to terminate). Each transform-step is
  passed the output state of the previous one. The output state of the
  final one is returned at the end."
  [transcade init-state]
  (loop [current-state init-state]
    (let [[was-it-changed new-state] (transform-step transcade current-state)]
      (case was-it-changed
        :new-state (recur new-state)
        :unchanged current-state))))