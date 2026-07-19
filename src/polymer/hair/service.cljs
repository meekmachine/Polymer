(ns polymer.hair.service
  (:require [polymer.hair.agency :as hair-agency]))

;; Compatibility adapter for the JS class-style API LoomLarge's Hair UI already
;; expects. This is not the Hair agency implementation. It dispatches to Polymer
;; Hair first; accepted runtime requests are then applied through the Embody
;; engine boundary that already owns scene changes.

(def HAIR_COLOR_PRESETS
  #js {:natural_black #js {:name "Natural Black" :baseColor "#1a1a1a" :emissive "#000000" :emissiveIntensity 0}
       :natural_brown #js {:name "Natural Brown" :baseColor "#4a3728" :emissive "#000000" :emissiveIntensity 0}
       :natural_blonde #js {:name "Natural Blonde" :baseColor "#e6c78a" :emissive "#000000" :emissiveIntensity 0}
       :natural_red #js {:name "Natural Red" :baseColor "#8b3a3a" :emissive "#000000" :emissiveIntensity 0}
       :natural_gray #js {:name "Natural Gray" :baseColor "#9e9e9e" :emissive "#000000" :emissiveIntensity 0}
       :natural_white #js {:name "Natural White" :baseColor "#f5f5f5" :emissive "#000000" :emissiveIntensity 0}
       :neon_blue #js {:name "Neon Blue" :baseColor "#00ffff" :emissive "#0000ff" :emissiveIntensity 0.8}
       :neon_pink #js {:name "Neon Pink" :baseColor "#ff00ff" :emissive "#ff1493" :emissiveIntensity 0.8}
       :neon_green #js {:name "Neon Green" :baseColor "#00ff00" :emissive "#00ff00" :emissiveIntensity 0.8}
       :electric_purple #js {:name "Electric Purple" :baseColor "#9d00ff" :emissive "#9d00ff" :emissiveIntensity 0.6}
       :fire_orange #js {:name "Fire Orange" :baseColor "#ff6600" :emissive "#ff3300" :emissiveIntensity 0.7}})

(def DEFAULT_HAIR_PHYSICS_CONFIG
  #js {:stiffness 7.5
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
       :idleClipDuration 10
       :impulseClipDuration 1.4})

(def DEFAULT_HAIR_PHYSICS_ENABLED false)

(defn data-map [value]
  (cond
    (map? value) value
    value (js->clj value :keywordize-keys true)
    :else {}))

(defn js-call [target method-name & args]
  (when target
    (when-let [method (aget target method-name)]
      (when (fn? method)
        (.apply method target (to-array args))))))

(defn clone-js [value]
  (clj->js (data-map value)))

(defn preset [key]
  (clone-js (aget HAIR_COLOR_PRESETS key)))

(defn default-state []
  {:hairColor (data-map (preset "natural_brown"))
   :eyebrowColor (data-map (preset "natural_brown"))
   :showOutline false
   :outlineColor "#00ff00"
   :outlineOpacity 1
   :parts {}})

(defn js-array->vec [value]
  (cond
    (array? value) (vec (array-seq value))
    (and value (fn? (aget value "forEach")))
    (let [out (atom [])]
      (.forEach value #(swap! out conj %))
      @out)
    :else []))

(defn normalize-object-ref [object]
  (let [input (data-map object)
        name (or (:name input) "unnamed")]
    {:name name
     :isEyebrow (boolean (:isEyebrow input))
     :isMesh (if (contains? input :isMesh) (boolean (:isMesh input)) true)}))

(defn object-state [state object-ref]
  (let [name (:name object-ref)
        color (if (:isEyebrow object-ref) (:eyebrowColor state) (:hairColor state))
        part-state (get-in state [:parts name])]
    (cond->
     {:color {:baseColor (:baseColor color)
              :emissive (:emissive color)
              :emissiveIntensity (:emissiveIntensity color)}
      :outline {:show (:showOutline state)
                :color (:outlineColor state)
                :opacity (:outlineOpacity state)}
      :visible (if (contains? part-state :visible) (:visible part-state) true)
      :isEyebrow (:isEyebrow object-ref)}
      (:scale part-state) (assoc :scale {:x (:scale part-state)
                                         :y (:scale part-state)
                                         :z (:scale part-state)})
      (:position part-state) (assoc :position {:x (nth (:position part-state) 0)
                                               :y (nth (:position part-state) 1)
                                               :z (nth (:position part-state) 2)}))))

(defn apply-state-to-engine! [engine objects state]
  (when engine
    (doseq [object-ref @objects]
      (when (:isMesh object-ref)
        (js-call engine
                 "applyHairStateToObject"
                 (:name object-ref)
                 (clj->js (object-state state object-ref)))))))

(defn notify! [subscribers state]
  (let [payload (clj->js state)]
    (doseq [subscriber @subscribers]
      (subscriber payload))))

(defn current-engine-physics [engine]
  (let [engine-config (or (js-call engine "getHairPhysicsConfig") #js {})
        enabled (if-let [is-enabled (js-call engine "isHairPhysicsEnabled")]
                  (boolean is-enabled)
                  DEFAULT_HAIR_PHYSICS_ENABLED)]
    {:config (merge (data-map DEFAULT_HAIR_PHYSICS_CONFIG) (data-map engine-config))
     :enabled enabled}))

(defn dispatch-config! [agency config]
  (js-call agency "dispatch" (clj->js {:type "configure" :config config})))

(defn service-event->config [event state]
  (let [payload (data-map event)]
    (case (:type payload)
      "SET_HAIR_COLOR" {:hairColor (:color payload)}
      "SET_EYEBROW_COLOR" {:eyebrowColor (:color payload)}
      "SET_HAIR_BASE_COLOR" {:hairColor (assoc (:hairColor state) :baseColor (:baseColor payload))}
      "SET_EYEBROW_BASE_COLOR" {:eyebrowColor (assoc (:eyebrowColor state) :baseColor (:baseColor payload))}
      "SET_HAIR_GLOW" {:hairColor (assoc (:hairColor state)
                                          :emissive (:emissive payload)
                                          :emissiveIntensity (:intensity payload))}
      "SET_EYEBROW_GLOW" {:eyebrowColor (assoc (:eyebrowColor state)
                                                :emissive (:emissive payload)
                                                :emissiveIntensity (:intensity payload))}
      "SET_OUTLINE" {:showOutline (boolean (:show payload))
                     :outlineColor (or (:color payload) (:outlineColor state))
                     :outlineOpacity (if (contains? payload :opacity)
                                       (:opacity payload)
                                       (:outlineOpacity state))}
      "SET_PART_VISIBILITY" {:parts (assoc-in (:parts state)
                                              [(:partName payload) :visible]
                                              (boolean (:visible payload)))}
      "SET_PART_SCALE" {:parts (assoc-in (:parts state)
                                         [(:partName payload) :scale]
                                         (:scale payload))}
      "SET_PART_POSITION" {:parts (assoc-in (:parts state)
                                            [(:partName payload) :position]
                                            (:position payload))}
      "RESET_TO_DEFAULT" (default-state)
      {})))

(defn create-service [engine]
  (let [state-atom (atom (default-state))
        objects (atom [])
        hair-mesh-names (atom [])
        subscribers (atom #{})
        physics (current-engine-physics engine)
        physics-config (atom (:config physics))
        physics-enabled (atom (:enabled physics))
        agency (hair-agency/create-hair-agency (clj->js {:hairColor (:hairColor @state-atom)
                                                         :eyebrowColor (:eyebrowColor @state-atom)}))
        disposed? (atom false)
        event-unsubscribe (atom nil)]
    (letfn [(apply-and-notify! []
              (apply-state-to-engine! engine objects @state-atom)
              (notify! subscribers @state-atom))
            (route-event! [event]
              (let [payload (data-map event)]
                (when (and (= "hair.requestRuntime" (:type payload))
                           (= "applyState" (:action payload)))
                  (swap! state-atom merge
                         {:hairColor (:hairColor payload)
                          :eyebrowColor (:eyebrowColor payload)
                          :showOutline (get-in payload [:outline :show])
                          :outlineColor (get-in payload [:outline :color])
                          :outlineOpacity (get-in payload [:outline :opacity])
                          :parts (or (:parts payload) {})})
                  (apply-and-notify!))
                (when (and (= "hair.requestRuntime" (:type payload))
                           (= "reset" (:action payload)))
                  (reset! state-atom (default-state))
                  (apply-and-notify!))))]
      (reset! event-unsubscribe (js-call agency "subscribeEvents" route-event!))
      #js {:registerObjects (fn [raw-objects]
                              (when-not @disposed?
                                (let [registered (or (js-call engine "registerHairObjects" raw-objects)
                                                     raw-objects)
                                      normalized (mapv normalize-object-ref (js-array->vec registered))]
                                  (reset! objects normalized)
                                  (reset! hair-mesh-names
                                          (->> normalized
                                               (filter #(and (:isMesh %) (not (:isEyebrow %))))
                                               (mapv :name)))
                                  (js-call agency "dispatch"
                                           (clj->js {:type "registerObjects"
                                                     :objects normalized})))))
           :send (fn [event]
                   (when-not @disposed?
                     (let [updates (service-event->config event @state-atom)]
                       (when (seq updates)
                         (swap! state-atom merge updates)
                         (dispatch-config! agency updates)))))
           :getState (fn [] (clj->js @state-atom))
           :subscribe (fn [callback]
                        (swap! subscribers conj callback)
                        (fn [] (swap! subscribers disj callback)))
           :animateHairMorph (fn
                               ([morph-key target-value]
                                (js-call engine "transitionMorph" morph-key target-value 300 (clj->js @hair-mesh-names)))
                               ([morph-key target-value duration-ms]
                                (js-call engine "transitionMorph" morph-key target-value duration-ms (clj->js @hair-mesh-names))))
           :setHairMorph (fn [morph-key value]
                           (if (seq @hair-mesh-names)
                             (js-call engine "setMorphOnMeshes" (clj->js @hair-mesh-names) morph-key value)
                             (js-call engine "setMorph" morph-key value)))
           :getAvailableHairMorphs (fn []
                                      (let [hair-object (first (filter #(and (:isMesh %) (not (:isEyebrow %))) @objects))]
                                        (or (when hair-object
                                              (js-call engine "getHairMorphTargets" (:name hair-object)))
                                            #js [])))
           :setPhysicsEnabled (fn [enabled]
                                (reset! physics-enabled (boolean enabled))
                                (js-call engine "setHairPhysicsEnabled" (boolean enabled)))
           :isPhysicsEnabled (fn [] @physics-enabled)
           :updatePhysicsConfig (fn [updates]
                                  (swap! physics-config merge (data-map updates))
                                  (js-call engine "setHairPhysicsConfig" (clj->js @physics-config)))
           :getPhysicsConfig (fn []
                               (clj->js (merge {:enabled @physics-enabled}
                                               @physics-config)))
           :dispose (fn []
                      (when-not @disposed?
                        (reset! disposed? true)
                        (when-let [unsubscribe @event-unsubscribe]
                          (unsubscribe))
                        (reset! subscribers #{})
                        (reset! objects [])
                        (reset! hair-mesh-names [])
                        (js-call agency "dispose")))})))

(defn HairService
  ([] (create-service nil))
  ([engine] (create-service engine)))
