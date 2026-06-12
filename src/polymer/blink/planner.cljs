(ns polymer.blink.planner
  (:require [polymer.blink.state :as state]))

(def fast-blink-frequency-threshold 36)

(defn random-factor [randomness random-value]
  (+ 1 (* (- random-value 0.5) randomness)))

(defn next-interval-ms [blink-state random-value]
  (let [frequency (:frequency blink-state)]
    (if (<= frequency 0)
      nil
      (* (/ 60 frequency) 1000 (random-factor (:randomness blink-state) random-value)))))

(defn trigger-options [options]
  (if options (js->clj options :keywordize-keys true) {}))

(defn resolve-blink-count [blink-state reason options random-value]
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
