(ns polymer.hair.state
  (:require [polymer.hair.domain :as domain]))

;; Hair state is a serializable profile/runtime ledger. Rendering engines decide
;; how to apply this data; the agency only normalizes config and records which
;; runtime requests have been scheduled.

(def natural-brown
  {:name "Natural Brown"
   :baseColor "#4a3728"
   :emissive "#000000"
   :emissiveIntensity 0})

(def default-physics
  {:enabled false
   :stiffness 7.5
   :damping 0.18
   :inertia 3.5
   :gravity 12
   :responseScale 2.5
   :idleSwayAmount 0.12
   :idleSwaySpeed 1
   :windStrength 0
   :windDirectionX 1
   :windDirectionZ 0
   :windTurbulence 0.3
   :windFrequency 1.4
   :idleClipDurationMs 10000
   :impulseClipDurationMs 1400
   :coalesceMs 16})

(def default-config
  {:physics default-physics
   :hairColor natural-brown
   :eyebrowColor natural-brown
   :showOutline false
   :outlineColor "#00ff00"
   :outlineOpacity 1
   :objects []
   :parts {}})

(defn normalize-physics
  [value]
  (let [input (merge default-physics (domain/data-map value))]
    {:enabled (boolean (:enabled input))
     :stiffness (domain/clamp 0 30 (domain/number-or (:stiffness input) (:stiffness default-physics)))
     :damping (domain/clamp 0 2 (domain/number-or (:damping input) (:damping default-physics)))
     :inertia (domain/clamp 0 20 (domain/number-or (:inertia input) (:inertia default-physics)))
     :gravity (domain/clamp 0 40 (domain/number-or (:gravity input) (:gravity default-physics)))
     :responseScale (domain/clamp 0 10 (domain/number-or (:responseScale input) (:responseScale default-physics)))
     :idleSwayAmount (domain/clamp 0 1 (domain/number-or (:idleSwayAmount input) (:idleSwayAmount default-physics)))
     :idleSwaySpeed (domain/clamp 0 5 (domain/number-or (:idleSwaySpeed input) (:idleSwaySpeed default-physics)))
     :windStrength (domain/clamp 0 5 (domain/number-or (:windStrength input) (:windStrength default-physics)))
     :windDirectionX (domain/clamp -1 1 (domain/number-or (:windDirectionX input) (:windDirectionX default-physics)))
     :windDirectionZ (domain/clamp -1 1 (domain/number-or (:windDirectionZ input) (:windDirectionZ default-physics)))
     :windTurbulence (domain/clamp 0 1 (domain/number-or (:windTurbulence input) (:windTurbulence default-physics)))
     :windFrequency (domain/clamp 0 10 (domain/number-or (:windFrequency input) (:windFrequency default-physics)))
     :idleClipDurationMs (int (domain/clamp 100 60000 (domain/number-or (:idleClipDurationMs input)
                                                                         (:idleClipDurationMs default-physics))))
     :impulseClipDurationMs (int (domain/clamp 50 5000 (domain/number-or (:impulseClipDurationMs input)
                                                                         (:impulseClipDurationMs default-physics))))
     :coalesceMs (int (domain/clamp 0 1000 (domain/number-or (:coalesceMs input)
                                                             (:coalesceMs default-physics))))}))

(defn normalize-config
  [config]
  (let [input (merge default-config (domain/data-map config))]
    {:physics (normalize-physics (:physics input))
     :hairColor (domain/normalize-color (:hairColor input) natural-brown)
     :eyebrowColor (domain/normalize-color (:eyebrowColor input) natural-brown)
     :showOutline (boolean (:showOutline input))
     :outlineColor (or (:outlineColor input) (:outlineColor default-config))
     :outlineOpacity (domain/clamp 0 1 (domain/number-or (:outlineOpacity input)
                                                        (:outlineOpacity default-config)))
     :objects (mapv domain/normalize-object-ref (or (:objects input) []))
     :parts (or (:parts input) {})}))

(defn default-state
  [config]
  (let [normalized (normalize-config config)]
    {:agency "hair"
     :status "idle"
     :hairColor (:hairColor normalized)
     :eyebrowColor (:eyebrowColor normalized)
     :showOutline (:showOutline normalized)
     :outlineColor (:outlineColor normalized)
     :outlineOpacity (:outlineOpacity normalized)
     :objects (:objects normalized)
     :parts (:parts normalized)
     :physics (:physics normalized)
     :lastMotion nil
     :lastPlan nil
     :lastRuntimeRequest nil
     :lastEvent nil
     :planCount 0
     :motionCount 0
     :runtimeRequestCount 0
     :resetCount 0
     :config normalized}))

(defn config->state
  [config]
  (default-state config))

(defn visible-state
  [state]
  (clj->js state))

(defn configure
  [state config]
  (let [merged (merge (:config state) (domain/data-map config))
        normalized (normalize-config merged)]
    (assoc state
           :hairColor (:hairColor normalized)
           :eyebrowColor (:eyebrowColor normalized)
           :showOutline (:showOutline normalized)
           :outlineColor (:outlineColor normalized)
           :outlineOpacity (:outlineOpacity normalized)
           :objects (:objects normalized)
           :parts (:parts normalized)
           :physics (:physics normalized)
           :config normalized)))

(defn reset-state
  [state]
  (-> (default-state (:config state))
      (assoc :resetCount (inc (:resetCount state)))))

(defn record-plan
  [state plan]
  (-> state
      (assoc :lastPlan plan)
      (update :planCount inc)))

(defn record-motion
  [state motion]
  (-> state
      (assoc :status "moving"
             :lastMotion motion
             :lastEvent {:type "hair.motionObserved"
                         :motion motion})
      (update :motionCount inc)))

(defn record-runtime-request
  [state request]
  (-> state
      (assoc :status (case (:action request)
                       "reset" "idle"
                       "applyMotion" "moving"
                       "applyState" "configured"
                       (:status state))
             :lastRuntimeRequest request
             :lastEvent request)
      (update :runtimeRequestCount inc)))
