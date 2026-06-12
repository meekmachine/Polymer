(ns polymer.blink.planner
  (:require [polymer.blink.state :as state]))

(defn random-factor [randomness spread]
  (+ 1 (* (- (rand) 0.5) randomness spread)))

(defn blink-interval-ms [blink-state]
  (let [frequency (:frequency blink-state)]
    (when (pos? frequency)
      (* (/ 60 frequency) 1000))))

(defn should-burst? [blink-state]
  (and (:burstEnabled blink-state)
       (> (:burstCount blink-state) 1)
       (< (rand) (:burstChance blink-state))))

(defn manual-plan [blink-state options]
  (let [opts (or options {})
        burst-count (int (state/clamp 1 8 (state/number-or (:burstCount opts) 1)))]
    {:kind (if (> burst-count 1) :burst :single)
     :count burst-count
     :duration (state/clamp 0.05 1.0 (state/number-or (:duration opts) (:duration blink-state)))
     :intensity (state/clamp 0 1 (state/number-or (:intensity opts) (:intensity blink-state)))
     :gap (state/clamp 0.02 1.0 (state/number-or (:burstGap opts) (:burstGap blink-state)))}))

(defn auto-plan [blink-state]
  (if (should-burst? blink-state)
    {:kind :burst
     :count (:burstCount blink-state)
     :duration (:duration blink-state)
     :intensity (:intensity blink-state)
     :gap (:burstGap blink-state)}
    {:kind :single
     :count 1
     :duration (:duration blink-state)
     :intensity (:intensity blink-state)
     :gap (:burstGap blink-state)}))

(defn plan-total-duration [plan]
  (+ (* (:count plan) (:duration plan))
     (* (max 0 (dec (:count plan))) (:gap plan))))

(defn next-delay-ms [blink-state plan]
  (let [base (or (blink-interval-ms blink-state) 0)
        varied (* base (random-factor (:randomness blink-state) 1.0))
        plan-ms (* 1000 (plan-total-duration plan))]
    (+ varied plan-ms)))
