(ns polymer.eye-head.state
  (:require [polymer.eye-head.domain :as domain]))

;; Eye/Head state tracks the local movement schedule. Runtime handles remain
;; private to the Animation agency; this agency only records plain request and
;; snippet metadata.

(def default-config
  {:enabled true
   :eyeTrackingEnabled true
   :headTrackingEnabled true
   :headFollowEyes true
   :eyeIntensity 1.0
   :headIntensity 0.5
   :headRoll 0
   :eyePriority 20
   :headPriority 15
   :snippetPriority 20
   :transitionDurationMs 240
   :returnToCenterDurationMs 300
   :coalesceMs 0
   :replaceExisting true})

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
     :eyeTrackingEnabled (boolean (input-value input :eyeTrackingEnabled :eye-tracking-enabled (:eyeTrackingEnabled default-config)))
     :headTrackingEnabled (boolean (input-value input :headTrackingEnabled :head-tracking-enabled (:headTrackingEnabled default-config)))
     :headFollowEyes (boolean (input-value input :headFollowEyes :head-follow-eyes (:headFollowEyes default-config)))
     :eyeIntensity (domain/clamp 0 2
                                  (domain/number-or (input-value input :eyeIntensity :eye-intensity nil)
                                                    (:eyeIntensity default-config)))
     :headIntensity (domain/clamp 0 2
                                   (domain/number-or (input-value input :headIntensity :head-intensity nil)
                                                     (:headIntensity default-config)))
     :headRoll (domain/clamp -1 1
                             (domain/number-or (input-value input :headRoll :head-roll nil)
                                               (:headRoll default-config)))
     :eyePriority (int (domain/clamp 0 100
                                     (domain/number-or (input-value input :eyePriority :eye-priority nil)
                                                       (:eyePriority default-config))))
     :headPriority (int (domain/clamp 0 100
                                      (domain/number-or (input-value input :headPriority :head-priority nil)
                                                        (:headPriority default-config))))
     :snippetPriority (int (domain/clamp 0 100
                                         (domain/number-or (input-value input :snippetPriority :snippet-priority nil)
                                                           (:snippetPriority default-config))))
     :transitionDurationMs (int (domain/clamp 50 3000
                                              (domain/number-or
                                               (input-value input :transitionDurationMs :transition-duration-ms nil)
                                               (:transitionDurationMs default-config))))
     :returnToCenterDurationMs (int (domain/clamp 50 3000
                                                  (domain/number-or
                                                   (input-value input :returnToCenterDurationMs :return-to-center-duration-ms nil)
                                                   (:returnToCenterDurationMs default-config))))
     :coalesceMs (int (domain/clamp 0 1000
                                    (domain/number-or (input-value input :coalesceMs :coalesce-ms nil)
                                                      (:coalesceMs default-config))))
     :replaceExisting (boolean (input-value input :replaceExisting :replace-existing (:replaceExisting default-config)))}))

(defn default-state
  [config]
  {:agency "eyeHeadTracking"
   :status "idle"
   :mode "manual"
   :currentTarget domain/zero-target
   :pendingRequest nil
   :lastRequest nil
   :lastSnippet nil
   :activeSnippetNames []
   :lastIgnored nil
   :lastPlan nil
   :scheduledCount 0
   :removedCount 0
   :resetCount 0
   :cancelCount 0
   :ignoredCount 0
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

(defn set-enabled
  [state enabled]
  (assoc-in state [:config :enabled] (boolean enabled)))

(defn record-plan
  [state plan]
  (assoc state :lastPlan plan))

(defn record-pending
  [state request]
  (assoc state
         :status "pending"
         :pendingRequest request))

(defn record-scheduled
  [state request snippet published-at]
  (-> state
      (assoc :status "tracking"
             :mode (:mode request)
             :currentTarget (:target request)
             :pendingRequest nil
             :lastRequest request
             :lastSnippet snippet
             :activeSnippetNames [(:name snippet)]
             :lastPlan nil
             :lastEvent {:type "animation.requestScheduleSnippet"
                         :name (:name snippet)
                         :requestId (:requestId request)
                         :at published-at})
      (update :scheduledCount inc)))

(defn record-removed
  [state name reason removed-at]
  (-> state
      (assoc :activeSnippetNames []
             :lastEvent {:type "animation.requestRemoveSnippet"
                         :name name
                         :reason reason
                         :at removed-at})
      (update :removedCount inc)))

(defn record-ignored
  [state ignored]
  (-> state
      (assoc :status "ignored"
             :lastIgnored ignored
             :pendingRequest nil
             :lastEvent ignored)
      (update :ignoredCount inc)))

(defn record-reset
  [state request snippet published-at]
  (-> (record-scheduled state request snippet published-at)
      (assoc :status "reset"
             :currentTarget domain/zero-target)
      (update :resetCount inc)))

(defn record-cancel
  [state request]
  (-> state
      (assoc :status "cancelled"
             :pendingRequest nil
             :activeSnippetNames []
             :lastEvent request)
      (update :cancelCount inc)))
