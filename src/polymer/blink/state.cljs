(ns polymer.blink.state)

(def default-state
  {:agency "blink"
   :enabled false
   :frequency 17
   :duration 0.15
   :intensity 1.0
   :randomness 0.3
   :leftEyeIntensity nil
   :rightEyeIntensity nil
   :burstEnabled true
   :burstChance 0.08
   :burstCount 2
   :burstGap 0.12
   :lastBlinkTime nil
   :scheduledBlinkCount 0
   :scheduledBurstCount 0})

(defn clamp [low high value]
  (-> value (max low) (min high)))

(defn number-or [value fallback]
  (if (number? value) value fallback))

(defn boolean-or [value fallback]
  (if (boolean? value) value fallback))

(defn normalize-state [state]
  (let [source (merge default-state state)]
    (assoc source
           :enabled (boolean-or (:enabled source) (:enabled default-state))
           :frequency (clamp 0 60 (number-or (:frequency source) (:frequency default-state)))
           :duration (clamp 0.05 1.0 (number-or (:duration source) (:duration default-state)))
           :intensity (clamp 0 1 (number-or (:intensity source) (:intensity default-state)))
           :randomness (clamp 0 1 (number-or (:randomness source) (:randomness default-state)))
           :leftEyeIntensity (when (number? (:leftEyeIntensity source))
                               (clamp 0 1 (:leftEyeIntensity source)))
           :rightEyeIntensity (when (number? (:rightEyeIntensity source))
                                (clamp 0 1 (:rightEyeIntensity source)))
           :burstEnabled (boolean-or (:burstEnabled source) (:burstEnabled default-state))
           :burstChance (clamp 0 1 (number-or (:burstChance source) (:burstChance default-state)))
           :burstCount (int (clamp 1 8 (number-or (:burstCount source) (:burstCount default-state))))
           :burstGap (clamp 0.02 1.0 (number-or (:burstGap source) (:burstGap default-state)))
           :scheduledBlinkCount (int (max 0 (number-or (:scheduledBlinkCount source) 0)))
           :scheduledBurstCount (int (max 0 (number-or (:scheduledBurstCount source) 0))))))

(defn js->state [value]
  (normalize-state (js->clj value :keywordize-keys true)))

(defn visible-state [state]
  (select-keys state
               [:enabled
                :frequency
                :duration
                :intensity
                :randomness
                :leftEyeIntensity
                :rightEyeIntensity
                :burstEnabled
                :burstChance
                :burstCount
                :burstGap
                :lastBlinkTime
                :scheduledBlinkCount
                :scheduledBurstCount]))

(defn apply-command [state command]
  (let [payload (js->clj command :keywordize-keys true)
        type (:type payload)]
    (case type
      "enable" (assoc state :enabled true)
      "disable" (assoc state :enabled false)
      "setFrequency" (assoc state :frequency (:value payload))
      "setDuration" (assoc state :duration (:value payload))
      "setIntensity" (assoc state :intensity (:value payload))
      "setRandomness" (assoc state :randomness (:value payload))
      "setLeftEyeIntensity" (assoc state :leftEyeIntensity (:value payload))
      "setRightEyeIntensity" (assoc state :rightEyeIntensity (:value payload))
      "setBurstEnabled" (assoc state :burstEnabled (:value payload))
      "setBurstChance" (assoc state :burstChance (:value payload))
      "setBurstCount" (assoc state :burstCount (:value payload))
      "setBurstGap" (assoc state :burstGap (:value payload))
      "configure" (merge state (:config payload))
      "reset" default-state
      state)))
