(ns polymer.eye-head.domain)

;; Eye/Head domain code normalizes incoming gaze requests and configuration.
;; It stays pure so movement policy can be tested without any animation runtime.

(def zero-target {:x 0 :y 0 :z 0})

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

(defn normalize-target
  ([value] (normalize-target value zero-target))
  ([value fallback]
   (let [input (data-map value)
         fallback (merge zero-target fallback)]
     {:x (clamp -1 1 (number-or (:x input) (:x fallback)))
      :y (clamp -1 1 (number-or (:y input) (:y fallback)))
      :z (number-or (:z input) (:z fallback))})))

(defn request-id
  [prefix now-ms]
  (str prefix ":" now-ms ":" (rand-int 1000000)))

(defn requested-target
  [command]
  (let [payload (data-map command)]
    (or (:target payload)
        (:gazeTarget payload)
        (:lookTarget payload)
        (:value payload))))

(defn has-requested-target?
  [command]
  (some? (requested-target command)))

(defn request-source
  [command]
  (let [payload (data-map command)]
    (or (:source payload)
        (:agency payload)
        "gaze")))

(defn normalize-gaze-request
  [command state now-ms]
  (let [payload (data-map command)
        config (:config state)
        target (normalize-target (requested-target payload))
        eye-enabled (if (contains? payload :eyeEnabled)
                      (boolean (:eyeEnabled payload))
                      true)
        head-enabled (if (contains? payload :headEnabled)
                       (boolean (:headEnabled payload))
                       true)
        head-follow-eyes (if (contains? payload :headFollowEyes)
                           (boolean (:headFollowEyes payload))
                           (:headFollowEyes config))]
    {:type "eyeHeadTracking.requestGaze"
     :agency "eyeHeadTracking"
     :sourceAgency (request-source payload)
     :requestId (or (:requestId payload) (request-id "eyeHeadTracking" now-ms))
     :target target
     :rawTarget (normalize-target (:rawTarget payload) target)
     :mode (or (:mode payload) (:mode state) "manual")
     :eyeEnabled (and (:eyeTrackingEnabled config) eye-enabled)
     :headEnabled (and (:headTrackingEnabled config) head-enabled)
     :headFollowEyes head-follow-eyes
     :eyeIntensity (number-or (:eyeIntensity payload) (:eyeIntensity config))
     :headIntensity (number-or (:headIntensity payload) (:headIntensity config))
     :headRoll (number-or (:headRoll payload) (:headRoll config))
     :eyeDurationMs (number-or (:eyeDurationMs payload)
                               (:transitionDurationMs config))
     :headDurationMs (number-or (:headDurationMs payload)
                                (:transitionDurationMs config))
     :createdAt (or (:createdAt payload) now-ms)}))

(defn movement-enabled?
  [request]
  (or (:eyeEnabled request)
      (and (:headEnabled request) (:headFollowEyes request))))

(defn normalize-reset-request
  [command state now-ms]
  (let [payload (data-map command)
        config (:config state)
        duration (number-or (:durationMs payload)
                            (number-or (:duration-ms payload)
                                       (:returnToCenterDurationMs config)))]
    {:type "eyeHeadTracking.requestReset"
     :agency "eyeHeadTracking"
     :sourceAgency (request-source payload)
     :requestId (or (:requestId payload) (request-id "eyeHeadTracking:reset" now-ms))
     :target zero-target
     :rawTarget zero-target
     :mode (:mode state)
     :eyeEnabled (if (contains? payload :eyes)
                   (and (:eyeTrackingEnabled config) (boolean (:eyes payload)))
                   (:eyeTrackingEnabled config))
     :headEnabled (if (contains? payload :head)
                    (and (:headTrackingEnabled config) (boolean (:head payload)))
                    (:headTrackingEnabled config))
     :headFollowEyes true
     :eyeIntensity (:eyeIntensity config)
     :headIntensity (:headIntensity config)
     :headRoll 0
     :eyeDurationMs duration
     :headDurationMs duration
     :createdAt now-ms}))

(defn normalize-cancel-request
  [command now-ms]
  (let [payload (data-map command)]
    {:type "eyeHeadTracking.requestCancel"
     :agency "eyeHeadTracking"
     :sourceAgency (request-source payload)
     :requestId (or (:requestId payload) (request-id "eyeHeadTracking:cancel" now-ms))
     :reason (or (:reason payload) "cancelled")
     :createdAt now-ms}))
