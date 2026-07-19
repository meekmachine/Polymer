(ns polymer.gaze.state
  (:require [polymer.gaze.domain :as domain]))

;; Gaze state is a local ledger of attention targets and accepted look requests.
;; It is serializable diagnostic data; high-frequency eye/head execution belongs
;; in the downstream movement agency that consumes Gaze requests.

(def default-config
  {:enabled true
   :eyesEnabled true
   :headEnabled true
   :headFollowEyes true
   :mirrored false
   :smoothFactor 0.25
   :minDelta 0.01
   :transitionDurationMs 300
   :eyeIntensity 1.0
   :headIntensity 0.5
   :coalesceMs 16})

(defn input-value
  [input camel-key kebab-key fallback]
  (let [camel (get input camel-key ::missing)
        kebab (get input kebab-key ::missing)]
    (cond
      (not= camel ::missing) camel
      (not= kebab ::missing) kebab
      :else fallback)))

(defn normalize-config
  [config]
  (let [input (merge default-config (domain/data-map config))]
    {:enabled (boolean (input-value input :enabled :enabled (:enabled default-config)))
     :eyesEnabled (boolean (input-value input :eyesEnabled :eyes-enabled (:eyesEnabled default-config)))
     :headEnabled (boolean (input-value input :headEnabled :head-enabled (:headEnabled default-config)))
     :headFollowEyes (boolean (input-value input :headFollowEyes :head-follow-eyes (:headFollowEyes default-config)))
     :mirrored (boolean (input-value input :mirrored :mirrored (:mirrored default-config)))
     :smoothFactor (domain/clamp 0 1
                                 (domain/number-or (input-value input :smoothFactor :smooth-factor nil)
                                                   (:smoothFactor default-config)))
     :minDelta (domain/clamp 0 1
                             (domain/number-or (input-value input :minDelta :min-delta nil)
                                               (:minDelta default-config)))
     :transitionDurationMs (int (domain/clamp 50 3000
                                              (domain/number-or
                                               (input-value input :transitionDurationMs :transition-duration-ms nil)
                                               (:transitionDurationMs default-config))))
     :eyeIntensity (domain/clamp 0 2
                                 (domain/number-or (input-value input :eyeIntensity :eye-intensity nil)
                                                   (:eyeIntensity default-config)))
     :headIntensity (domain/clamp 0 2
                                  (domain/number-or (input-value input :headIntensity :head-intensity nil)
                                                    (:headIntensity default-config)))
     :coalesceMs (int (domain/clamp 0 1000
                                    (domain/number-or (input-value input :coalesceMs :coalesce-ms nil)
                                                      (:coalesceMs default-config))))}))

(defn default-state
  [config]
  {:agency "gaze"
   :status "idle"
   :mode "manual"
   :active false
   :rawTarget domain/zero-target
   :target domain/zero-target
   :lastRequestedTarget domain/zero-target
   :pendingRequest nil
   :lastRequest nil
   :lastIgnored nil
   :lastPlan nil
   :lastEvent nil
   :receivedCount 0
   :plannedCount 0
   :requestedCount 0
   :ignoredCount 0
   :resetCount 0
   :cancelCount 0
   :config (normalize-config config)})

(defn config->state
  [config]
  (default-state config))

(defn visible-state
  [state]
  (clj->js state))

(defn configure
  [state config]
  (assoc state :config (normalize-config (merge (:config state)
                                                (domain/data-map config)))))

(defn reset-state
  [state]
  (default-state (:config state)))

(defn set-mode
  [state mode now-ms]
  (assoc state
         :mode (or mode "manual")
         :lastEvent {:type "gaze.status"
                     :status (:status state)
                     :mode (or mode "manual")
                     :at now-ms}))

(defn set-active
  [state active now-ms]
  (assoc state
         :active (boolean active)
         :lastEvent {:type "gaze.status"
                     :status (:status state)
                     :active (boolean active)
                     :at now-ms}))

(defn set-enabled
  [state enabled now-ms]
  (assoc-in (assoc state
                   :lastEvent {:type "gaze.status"
                               :status (if enabled "enabled" "disabled")
                               :at now-ms})
            [:config :enabled]
            (boolean enabled)))

(defn record-plan
  [state plan]
  (-> state
      (assoc :status "planning"
             :rawTarget (:rawTarget plan)
             :target (:target plan)
             :lastPlan plan
             :lastEvent {:type "gaze.targetPlanned"
                         :requestId (:requestId plan)
                         :at (:createdAt plan)})
      (update :receivedCount inc)
      (update :plannedCount inc)))

(defn record-pending
  [state request]
  (assoc state
         :status "pending"
         :pendingRequest request
         :lastEvent {:type "gaze.requestQueued"
                     :requestId (:requestId request)
                     :at (:queuedAt request)}))

(defn record-requested
  [state request published-at]
  (-> state
      (assoc :status "requested"
             :pendingRequest nil
             :lastRequest (assoc request :publishedAt published-at)
             :lastRequestedTarget (:target request)
             :lastEvent {:type "eyeHeadTracking.requestGaze"
                         :requestId (:requestId request)
                         :at published-at})
      (update :requestedCount inc)))

(defn record-ignored
  [state ignored]
  (-> state
      (assoc :status "ignored"
             :lastIgnored ignored
             :lastEvent {:type "gaze.targetIgnored"
                         :reason (:reason ignored)
                         :requestId (:requestId ignored)
                         :at (:ignoredAt ignored)})
      (update :ignoredCount inc)))

(defn record-reset
  [state request]
  (-> state
      (assoc :status "reset"
             :rawTarget domain/zero-target
             :target domain/zero-target
             :lastRequestedTarget domain/zero-target
             :pendingRequest nil
             :lastRequest request
             :lastEvent {:type "eyeHeadTracking.requestReset"
                         :requestId (:requestId request)
                         :at (:requestedAt request)})
      (update :resetCount inc)))

(defn record-cancel
  [state request]
  (-> state
      (assoc :status "cancelled"
             :pendingRequest nil
             :lastEvent {:type "eyeHeadTracking.requestCancel"
                         :requestId (:requestId request)
                         :at (:requestedAt request)})
      (update :cancelCount inc)))
