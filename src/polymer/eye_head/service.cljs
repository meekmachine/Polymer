(ns polymer.eye-head.service
  (:require [polymer.animation.service :as animation-service]
            [polymer.eye-head.agency :as eye-head-agency]))

;; Compatibility adapter for the JavaScript service API LoomLarge already uses.
;; This is not the Eye/Head agency implementation. Movement work enters
;; Polymer's Eye/Head agency first; this adapter only preserves the historical
;; API while routing accepted animation requests into Polymer Animation.

(def EYE_AUS
  #js {:BOTH_LOOK_LEFT 61
       :BOTH_LOOK_RIGHT 62
       :BOTH_LOOK_UP 63
       :BOTH_LOOK_DOWN 64
       :BLINK 43
       :WIDE 5
       :SQUINT 7})

(def HEAD_AUS
  #js {:TURN_LEFT 51
       :TURN_RIGHT 52
       :TURN_UP 53
       :TURN_DOWN 54
       :TILT_LEFT 55
       :TILT_RIGHT 56})

(def DEFAULT_EYE_HEAD_CONFIG
  #js {:gazeMode "engine"
       :eyeTrackingEnabled false
       :headTrackingEnabled false
       :headFollowEyes true
       :eyeIntensity 1.0
       :headIntensity 0.5
       :returnToNeutralEnabled false
       :returnToNeutralDelay 3000
       :returnToNeutralDuration 800
       :useAnimationAgency true})

(def DEFAULT_ANIMATION_KEYS
  #js {:EYE_LOOK_LEFT "eyeHeadTracking/eyeLookLeft"
       :EYE_LOOK_RIGHT "eyeHeadTracking/eyeLookRight"
       :EYE_LOOK_UP "eyeHeadTracking/eyeLookUp"
       :EYE_LOOK_DOWN "eyeHeadTracking/eyeLookDown"
       :EYE_BLINK "eyeHeadTracking/eyeBlink"
       :HEAD_TURN_LEFT "eyeHeadTracking/headTurnLeft"
       :HEAD_TURN_RIGHT "eyeHeadTracking/headTurnRight"
       :HEAD_TURN_UP "eyeHeadTracking/headTurnUp"
       :HEAD_TURN_DOWN "eyeHeadTracking/headTurnDown"
       :HEAD_TILT_LEFT "eyeHeadTracking/headTiltLeft"
       :HEAD_TILT_RIGHT "eyeHeadTracking/headTiltRight"})

(defn data-map [value]
  (cond
    (map? value) value
    value (js->clj value :keywordize-keys true)
    :else {}))

(defn finite-number? [value]
  (and (number? value) (js/isFinite value)))

(defn number-or [value fallback]
  (if (finite-number? value) value fallback))

(defn js-call [target method-name & args]
  (when target
    (when-let [method (aget target method-name)]
      (when (fn? method)
        (.apply method target (to-array args))))))

(defn runtime-prop [value property-name]
  (cond
    (map? value) (get value (keyword property-name))
    value (aget value property-name)
    :else nil))

(defn normalize-target [target fallback]
  (let [input (merge {:x 0 :y 0 :z 0} fallback (data-map target))]
    {:x (max -1 (min 1 (number-or (:x input) 0)))
     :y (max -1 (min 1 (number-or (:y input) 0)))
     :z (number-or (:z input) 0)}))

(defn service-config [config]
  (let [input (data-map config)
        transition (number-or (:agencyTransitionDuration input)
                              (number-or (:transitionDurationMs input) 240))
        neutral-duration (number-or (:returnToNeutralDuration input)
                                    (number-or (:returnToCenterDurationMs input) 300))]
    {:enabled true
     :eyeTrackingEnabled (boolean (:eyeTrackingEnabled input))
     :headTrackingEnabled (boolean (:headTrackingEnabled input))
     :headFollowEyes (if (contains? input :headFollowEyes)
                       (boolean (:headFollowEyes input))
                       true)
     :eyeIntensity (number-or (:eyeIntensity input) 1.0)
     :headIntensity (number-or (:headIntensity input) 0.5)
     :transitionDurationMs transition
     :returnToCenterDurationMs neutral-duration
     :coalesceMs (number-or (:coalesceMs input) 0)
     :replaceExisting true}))

(defn service-state [state-atom mode-atom]
  (let [snapshot @state-atom
        agency-state (data-map snapshot)
        config (or (:config agency-state) {})
        current-target (or (:currentTarget agency-state) {:x 0 :y 0 :z 0})]
    #js {:eyeStatus (if (get config :eyeTrackingEnabled) "tracking" "idle")
         :headStatus (if (get config :headTrackingEnabled) "tracking" "idle")
         :currentGaze (clj->js current-target)
         :targetGaze (clj->js current-target)
         :eyeIntensity (number-or (:eyeIntensity config) 1.0)
         :lastBlinkTime (:lastBlinkTime agency-state)
         :headIntensity (number-or (:headIntensity config) 0.5)
         :headFollowTimer nil
         :isSpeaking (boolean (:isSpeaking agency-state))
         :isListening (boolean (:isListening agency-state))
         :returnToNeutralTimer nil
         :lastGazeUpdateTime (or (:lastGazeUpdateTime agency-state) 0)
         :mode @mode-atom}))

(defn create-blink-snippet []
  (let [name (str "eyeHeadTracking:blink:" (.now js/Date))]
    {:name name
     :curves {"43" [{:time 0.0 :intensity 0.0 :inherit true}
                    {:time 0.045 :intensity 1.0}
                    {:time 0.13 :intensity 0.0}]}
     :channels [{:target {:type "au" :id 43}
                 :keyframes [{:time 0.0 :intensity 0.0 :inherit true}
                             {:time 0.045 :intensity 1.0}
                             {:time 0.13 :intensity 0.0}]}]
     :maxTime 0.13
     :loop false
     :snippetCategory "eyeHeadTracking"
     :snippetPriority 25
     :snippetBlendMode "additive"
     :snippetPlaybackRate 1.0
     :snippetIntensityScale 1.0}))

(defn animation-target [config]
  (or (runtime-prop config "animationAgency")
      (when-let [engine (runtime-prop config "engine")]
        (animation-service/createAnimationService engine))))

(defn schedule-animation! [anim snippet options]
  (or (js-call anim "schedule" (clj->js snippet) (clj->js options))
      (js-call anim "scheduleSnippet" (clj->js snippet) (clj->js options))))

(defn remove-animation! [anim name]
  (or (js-call anim "remove" name)
      (js-call anim "removeSnippet" name)))

(defn createEyeHeadTrackingService
  ([]
   (createEyeHeadTrackingService nil nil))
  ([config]
   (createEyeHeadTrackingService config nil))
  ([config callbacks]
   (let [config-atom (atom (service-config config))
         mode-atom (atom (or (:gazeMode (data-map config)) "manual"))
         state-atom (atom (merge {:isSpeaking false
                                  :isListening false
                                  :lastBlinkTime nil
                                  :lastGazeUpdateTime 0}
                                 {:config @config-atom
                                  :currentTarget {:x 0 :y 0 :z 0}}))
         agency (eye-head-agency/create-eye-head-tracking-agency (clj->js @config-atom))
         anim (animation-target config)
         started? (atom false)
         disposed? (atom false)
         unsubscribers (atom [])]
     (letfn [(dispatch! [command]
               (when-not @disposed?
                 (js-call agency "dispatch" (clj->js command))))
             (configure! [next-config]
               (swap! config-atom merge (service-config next-config))
               (swap! state-atom assoc :config @config-atom)
               (dispatch! {:type "configure" :config @config-atom}))
             (route-event! [event]
               (let [payload (data-map event)]
                 (case (:type payload)
                   "animation.requestScheduleSnippet"
                   (schedule-animation! anim (:snippet payload) (or (:options payload) {:autoPlay true}))

                   "animation.requestRemoveSnippet"
                   (remove-animation! anim (:name payload))

                   "error"
                   (js-call callbacks "onError" (js/Error. (or (:message payload)
                                                               "Eye/head tracking error")))

                   nil)
                 (when (= "animation.requestScheduleSnippet" (:type payload))
                   (swap! state-atom assoc
                          :currentTarget (get-in payload [:snippet :metadata :target] (:currentTarget @state-atom))
                          :lastGazeUpdateTime (.now js/Date)))))
             (teardown! []
               (doseq [unsubscribe @unsubscribers]
                 (unsubscribe))
               (reset! unsubscribers []))]
       (swap! unsubscribers conj (js-call agency "subscribeEvents" route-event!))
       #js {:start (fn []
                     (when-not @disposed?
                       (reset! started? true)
                       (dispatch! {:type "enable"})))
            :stop (fn []
                    (when-not @disposed?
                      (reset! started? false)
                      (dispatch! {:type "cancel" :reason "stopped"})))
            :setGazeTarget (fn [target]
                             (let [normalized (normalize-target target nil)]
                               (swap! state-atom assoc
                                      :currentTarget normalized
                                      :lastGazeUpdateTime (.now js/Date))
                               (dispatch! {:type "setTarget"
                                           :target normalized
                                           :mode @mode-atom})))
            :resetToNeutral (fn
                              ([] (dispatch! {:type "reset"}))
                              ([duration-ms]
                               (dispatch! {:type "reset" :durationMs duration-ms})))
            :blink (fn []
                     (when-not @disposed?
                       (let [snippet (create-blink-snippet)]
                         (swap! state-atom assoc :lastBlinkTime (.now js/Date))
                         (schedule-animation! anim snippet {:autoPlay true
                                                           :sourceAgency "eyeHeadTracking"}))))
            :setSpeaking (fn [is-speaking]
                           (swap! state-atom assoc :isSpeaking (boolean is-speaking))
                           (if is-speaking
                             (js-call callbacks "onHeadStart")
                             (js-call callbacks "onHeadStop")))
            :setListening (fn [is-listening]
                            (swap! state-atom assoc :isListening (boolean is-listening))
                            (if is-listening
                              (js-call callbacks "onEyeStart")
                              (js-call callbacks "onEyeStop")))
            :setEyeBlendWeight (fn [_value] nil)
            :setHeadBlendWeight (fn [_value] nil)
            :updateConfig configure!
            :getState (fn [] (service-state state-atom mode-atom))
            :getMachineContext (fn []
                                 #js {:config (clj->js @config-atom)
                                      :mode @mode-atom
                                      :target (clj->js (:currentTarget @state-atom))
                                      :lastApplied (clj->js (:currentTarget @state-atom))
                                      :status #js {:eye (if (:eyeTrackingEnabled @config-atom) "tracking" "idle")
                                                   :head (if (:headTrackingEnabled @config-atom) "tracking" "idle")}})
            :getSnippets (fn [] #js {:eye (js/Map.) :head (js/Map.)})
            :setMode (fn [mode]
                       (reset! mode-atom mode)
                       (dispatch! {:type "configure" :config {:mode mode}}))
            :getMode (fn [] @mode-atom)
            :subscribeToWebcam (fn [_callback] (fn [] nil))
            :getWebcamVideoElement (fn [] nil)
            :isWebcamActive (fn [] false)
            :dispose (fn []
                       (when-not @disposed?
                         (reset! disposed? true)
                         (teardown!)
                         (js-call agency "dispose")))}))))
