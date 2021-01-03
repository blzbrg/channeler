(ns channeler.transcade
  (:require [clojure.core.async :as async]))

(defprotocol Transformer
  "A single transformer, a sequence of these combine to be a transcade"
  (applicable? [this ctx state] "Whether this transformer is applicable to the given state")
  (transform [this ctx state] "Take state and transform it, returning new state."))

(defn ^:private chan-await
  "Transform state by waiting for one of the chans in ::awaits to
  produce a value. When this happens, store a mapping from the channel
  to the value in ::await-results. If the channel is closed, the
  special value ::closed is stored.

  If a transformer expects to receive more than one message on the
  channel is should re-add the channel to ::awaits after it has
  processed the value retrived."
  [state]
  (let [[val chan] (async/alts!! (vec (state ::awaits)))
        eff-val (if (nil? val) ::closed val)]
    (-> state
        (assoc-in [::await-results chan] eff-val) ; put in results
        (update-in [::awaits] disj chan)))) ; remove from awaits

(defn push-awaits
  [post chan]
  (update post ::awaits (fn [awaits] (conj (or awaits #{}) chan))))

(defn pop-awaits-result
  [post chan-to-pop]
  (let [res (get-in post [::await-results chan-to-pop])]
    [res (update-in post [::await-results] dissoc chan-to-pop)]))

(defn ^:private select-applicable
  [transcade ctx state]
  (first (drop-while (fn [trans] (not (applicable? trans ctx state)))
                     (transcade :transformers))))

(defn transform-step
  "A single step of the transform. Either executes one transform and
  returns [:new-state new-state] or executes no transform (because
  none are applicable) and returns [:unchanged state]."
  [transcade ctx state]
  (if-let [trans (select-applicable transcade ctx state)]
    [:new-state (transform trans ctx state)]
    (if (not (empty? (state ::awaits))) ; awaits present and non-empty
      [:new-state (chan-await state )]
      [:unchanged state])))

(defn inline-loop
  "Perform transform-step until transform-step returns [:unchanged
  _] (which indicates it is time to terminate). Each transform-step is
  passed the output state of the previous one. The output state of the
  final one is returned at the end."
  [transcade ctx init-state]
  (loop [current-state init-state]
    (let [[was-it-changed new-state] (transform-step transcade ctx current-state)]
      (case was-it-changed
        :new-state (recur new-state)
        :unchanged current-state))))

(defn ^:private eff-val
  [val]
  (if (nil? val) ::closed val))

(defn coroutine-loop
  "Return channel that will yield the final state of running the given
  transcade on the init-state, using the context ctx. The channel will
  only yield only this item and then be closed.

  Transforms cannot use >!, >!!, etc. because they will be separated
  from the top of the go block by a function call, but they can use
  put! and store channels in ::awaits to request that the transcate
  dequeue a value from the channel on their behalf."
  [transcade ctx init-state]
  (async/go-loop [current-state init-state]
    (if-let [trans (select-applicable transcade ctx current-state)]
      ;; if a transcade is eligible straight-away, do it
      (recur (transform trans ctx current-state))
      ;; if none are eligible, check if there are any chans to await
      (if (not (empty? (current-state ::awaits)))
        ;; if there are awaits, wait for one of them
        (let [[chan val] (async/alts! (vec (current-state ::awaits)))]
          (-> current-state
              (assoc-in [::await-results chan] (eff-val val)) ; put in results
              (update-in [::awaits] disj chan))) ; remove from awaits
        ;; if there are no awaits, we are done
        current-state))))