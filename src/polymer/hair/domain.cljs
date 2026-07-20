(ns polymer.hair.domain
  (:require [clojure.string :as str]))

;; Hair domain helpers are pure data transforms. They classify object facts,
;; clamp motion inputs, and build runtime request payloads without importing a
;; render engine or mutating scene objects.

(def zero-vector3 {:x 0 :y 0 :z 0})

(defn finite-number?
  [value]
  (and (number? value) (js/isFinite value)))

(defn number-or
  [value fallback]
  (if (finite-number? value) value fallback))

(defn clamp
  [lo hi value]
  (min hi (max lo value)))

(defn data-map
  [value]
  (cond
    (map? value) value
    value (js->clj value :keywordize-keys true)
    :else {}))

(defn normalize-vector3
  [value]
  (let [input (data-map value)]
    {:x (number-or (:x input) 0)
     :y (number-or (:y input) 0)
     :z (number-or (:z input) 0)}))

(defn vector-magnitude
  [v]
  (js/Math.sqrt (+ (* (:x v) (:x v))
                   (* (:y v) (:y v))
                   (* (:z v) (:z v)))))

(defn normalize-color
  [value fallback]
  (if (string? value)
    (assoc fallback :baseColor value)
    (let [input (merge fallback (data-map value))]
      {:name (or (:name input) (:name fallback))
       :baseColor (or (:baseColor input)
                      (:base-color input)
                      (:baseColor fallback))
       :emissive (or (:emissive input) (:emissive fallback))
       :emissiveIntensity (clamp 0 1 (number-or (or (:emissiveIntensity input)
                                                    (:emissive-intensity input))
                                                (:emissiveIntensity fallback)))})))

(defn eyebrow-name?
  [name]
  (let [lower (str/lower-case (or name ""))]
    (or (str/includes? lower "brow")
        (str/includes? lower "eyebrow"))))

(defn normalize-object-ref
  [object]
  (let [input (data-map object)
        name (or (:name input) "unnamed")]
    {:name name
     :isEyebrow (boolean (if (contains? input :isEyebrow)
                           (:isEyebrow input)
                           (eyebrow-name? name)))
     :isMesh (boolean (if (contains? input :isMesh)
                        (:isMesh input)
                        true))}))

(defn motion-request
  [motion config sequence now-ms]
  (let [velocity (normalize-vector3 (or (:velocity motion) (:delta motion) motion))
        magnitude (vector-magnitude velocity)
        response-scale (:responseScale config)
        intensity (clamp 0 1 (* magnitude response-scale))]
    {:type "hair.requestRuntime"
     :agency "hair"
     :targetAgency "hair-runtime"
     :action "applyMotion"
     :sequence sequence
     :mode (if (> intensity 0.02) "followMotion" "settle")
     :velocity velocity
     :intensity intensity
     :durationMs (:impulseClipDurationMs config)
     :requestedAt now-ms}))

(defn material-request
  [state now-ms]
  {:type "hair.requestRuntime"
   :agency "hair"
   :targetAgency "hair-runtime"
   :action "applyState"
   :hairColor (:hairColor state)
   :eyebrowColor (:eyebrowColor state)
   :outline {:show (:showOutline state)
             :color (:outlineColor state)
             :opacity (:outlineOpacity state)}
   :parts (:parts state)
   :physics (:physics state)
   :objects (:objects state)
   :requestedAt now-ms})

(defn reset-request
  [now-ms]
  {:type "hair.requestRuntime"
   :agency "hair"
   :targetAgency "hair-runtime"
   :action "reset"
   :requestedAt now-ms})
