(ns polymer.blink.state)

(def default-state
  {:agency "blink"
   :enabled false
   :frequency 17
   :duration 0.15
   :intensity 1
   :randomness 0.3
   :leftEyeIntensity nil
   :rightEyeIntensity nil
   :burstEnabled true
   :burstFrequency 0.08
   :burstCount 2
   :burstGap 0.08
   :lastBlinkTime nil
   :scheduledBlinkCount 0
   :scheduledBurstCount 0})

(defn clamp [lo hi value]
  (-> value (max lo) (min hi)))

(defn finite-number? [value]
  (and (number? value) (js/isFinite value)))

(defn number-or [value fallback]
  (if (finite-number? value) value fallback))

(defn maybe-intensity [value]
  (when (finite-number? value)
    (clamp 0 1 value)))

(defn sanitize-state [state]
  (-> state
      (update :enabled boolean)
      (update :frequency #(clamp 0 60 (number-or % (:frequency default-state))))
      (update :duration #(clamp 0.05 1 (number-or % (:duration default-state))))
      (update :intensity #(clamp 0 1 (number-or % (:intensity default-state))))
      (update :randomness #(clamp 0 1 (number-or % (:randomness default-state))))
      (update :leftEyeIntensity maybe-intensity)
      (update :rightEyeIntensity maybe-intensity)
      (update :burstEnabled boolean)
      (update :burstFrequency #(clamp 0 1 (number-or % (:burstFrequency default-state))))
      (update :burstCount #(int (clamp 1 8 (number-or % (:burstCount default-state)))))
      (update :burstGap #(clamp 0.02 0.5 (number-or % (:burstGap default-state))))))

(defn config->state [config]
  (let [input (if config (js->clj config :keywordize-keys true) {})]
    (sanitize-state (merge default-state input))))

(defn visible-state [state]
  (clj->js state))

(defn configure [state config]
  (let [input (if config (js->clj config :keywordize-keys true) {})]
    (sanitize-state (merge state input))))

(defn apply-command [state payload]
  (let [type (:type payload)]
    (case type
      "enable" (assoc state :enabled true)
      "disable" (assoc state :enabled false)
      "setFrequency" (sanitize-state (assoc state :frequency (:value payload)))
      "setDuration" (sanitize-state (assoc state :duration (:value payload)))
      "setIntensity" (sanitize-state (assoc state :intensity (:value payload)))
      "setRandomness" (sanitize-state (assoc state :randomness (:value payload)))
      "setLeftEyeIntensity" (sanitize-state (assoc state :leftEyeIntensity (:value payload)))
      "setRightEyeIntensity" (sanitize-state (assoc state :rightEyeIntensity (:value payload)))
      "setBurstEnabled" (sanitize-state (assoc state :burstEnabled (:value payload)))
      "setBurstFrequency" (sanitize-state (assoc state :burstFrequency (:value payload)))
      "setBurstCount" (sanitize-state (assoc state :burstCount (:value payload)))
      "setBurstGap" (sanitize-state (assoc state :burstGap (:value payload)))
      "configure" (configure state (:config payload))
      "reset" default-state
      state)))

(defn record-plan [state plan now-ms]
  (let [blink-count (:blink-count plan)]
    (-> state
        (assoc :lastBlinkTime now-ms)
        (update :scheduledBlinkCount + blink-count)
        (update :scheduledBurstCount + (if (> blink-count 1) 1 0)))))
