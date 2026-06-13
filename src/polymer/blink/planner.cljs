(ns polymer.blink.planner
  (:require [polymer.blink.state :as state]))

;; The planner is pure decision logic: given Blink state, a trigger reason, and
;; optional trigger overrides, it returns a plan map. It does not mutate state,
;; set timers, or emit effects.

(def fast-blink-frequency-threshold 36)

(defn random-factor [randomness random-value]
  ;; Randomness stretches or shrinks the next automatic blink interval around
  ;; the frequency-derived baseline.
  (+ 1 (* (- random-value 0.5) randomness)))

(defn next-interval-ms [blink-state random-value]
  ;; Frequency is stored as blinks per minute, so the baseline interval is
  ;; 60/frequency seconds. A disabled scheduler handles whether this value is
  ;; used; the planner only answers the interval question.
  (let [frequency (:frequency blink-state)]
    (if (<= frequency 0)
      nil
      (* (/ 60 frequency) 1000 (random-factor (:randomness blink-state) random-value)))))

(defn trigger-options [options]
  ;; JS callers may omit options. Normalize once so the rest of the planner can
  ;; stay in ordinary Clojure data.
  (if options (js->clj options :keywordize-keys true) {}))

(defn resolve-blink-count [blink-state reason options random-value]
  ;; Manual triggers may request an exact burst count. Automatic triggers choose
  ;; a burst only some of the time, controlled by burstFrequency.
  (let [manual-count (:burstCount options)]
    (cond
      (state/finite-number? manual-count)
      (int (state/clamp 1 8 manual-count))

      (and (= reason "auto")
           (:burstEnabled blink-state)
           (< random-value (:burstFrequency blink-state)))
      (:burstCount blink-state)

      :else 1)))

(defn make-plan
  ([blink-state reason options]
   (make-plan blink-state reason options (js/Math.random)))
  ([blink-state reason options random-value]
   ;; A plan is the normalized, immutable description of one blink opportunity.
   ;; The scheduler records it into state and turns it into snippet/effect data.
   (let [options (trigger-options options)
         blink-count (resolve-blink-count blink-state reason options random-value)
         duration (state/clamp 0.05 1 (state/number-or (:duration options) (:duration blink-state)))
         gap (state/clamp 0.02 0.5 (state/number-or (:burstGap options) (:burstGap blink-state)))
         intensity (state/clamp 0 1 (state/number-or (:intensity options) (:intensity blink-state)))
         now-ms (.now js/Date)]
     {:agency "blink"
      :name (str "polymer:blink:" now-ms)
      :reason reason
      :blink-count blink-count
      :duration duration
      :burst-gap gap
      :intensity intensity
      :created-at now-ms
      :fast? (or (>= (:frequency blink-state) fast-blink-frequency-threshold)
                 (> blink-count 2))})))
