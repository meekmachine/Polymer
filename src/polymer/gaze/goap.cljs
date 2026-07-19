(ns polymer.gaze.goap
  (:require [polymer.gaze.domain :as domain]))

;; The Gaze planner is local GOAP-style decision logic. It decides whether a
;; target fact should become a look request, but it does not publish streams or
;; mutate scheduler queues.

(def supported-command-types
  #{"configure"
    "setMode"
    "set-mode"
    "setActive"
    "set-active"
    "enable"
    "disable"
    "setTarget"
    "set-target"
    "focusTarget"
    "attention.fact"
    "camera.fact"
    "reset"
    "cancel"})

(defn command-type
  [command]
  (:type command))

(defn request-id
  [prefix now-ms]
  (str prefix ":" now-ms ":" (rand-int 1000000)))

(defn enabled-for-request?
  [config options]
  (and (:enabled config)
       (or (if (contains? options :eyeEnabled)
             (boolean (:eyeEnabled options))
             (:eyesEnabled config))
           (if (contains? options :headEnabled)
             (boolean (:headEnabled options))
             (:headEnabled config)))))

(defn target-request
  [plan state options source label]
  {:type "eyeHeadTracking.requestGaze"
   :agency "gaze"
   :targetAgency "eyeHeadTracking"
   :requestId (:requestId plan)
   :source source
   :label label
   :mode (:mode state)
   :target (:target plan)
   :rawTarget (:rawTarget plan)
   :previousTarget (:previousTarget plan)
   :eyeEnabled (if (contains? options :eyeEnabled)
                 (boolean (:eyeEnabled options))
                 (get-in state [:config :eyesEnabled]))
   :headEnabled (if (contains? options :headEnabled)
                  (boolean (:headEnabled options))
                  (get-in state [:config :headEnabled]))
   :headFollowEyes (if (contains? options :headFollowEyes)
                     (boolean (:headFollowEyes options))
                     (get-in state [:config :headFollowEyes]))
   :eyeIntensity (get-in state [:config :eyeIntensity])
   :headIntensity (get-in state [:config :headIntensity])
   :eyeDurationMs (:eyeDurationMs plan)
   :headDurationMs (:headDurationMs plan)
   :createdAt (:createdAt plan)})

(defn target-steps
  [command state now-ms]
  (let [payload (domain/data-map command)
        options (domain/data-map (:options payload))
        target-fact (domain/command-target payload)]
    (if-not target-fact
      [{:op "fail"
        :reason "missing-target"
        :commandType (:type payload)}]
      (let [plan (assoc (domain/plan-target (:target target-fact)
                                            (:lastRequestedTarget state)
                                            (:config state)
                                            options
                                            now-ms)
                        :requestId (request-id "gaze" now-ms)
                        :source (:source target-fact)
                        :label (:label target-fact)
                        :score (:score target-fact))
            request (target-request plan state options (:source target-fact) (:label target-fact))]
        (cond
          (not (enabled-for-request? (:config state) options))
          [{:op "record-target" :plan plan}
           {:op "publish-target-received" :plan plan}
           {:op "ignore-target" :plan plan :reason "disabled"}]

          (not (:accepted plan))
          [{:op "record-target" :plan plan}
           {:op "publish-target-received" :plan plan}
           {:op "publish-target-planned" :plan plan}
           {:op "ignore-target" :plan plan :reason "min-delta"}]

          :else
          [{:op "record-target" :plan plan}
           {:op "publish-target-received" :plan plan}
           {:op "publish-target-planned" :plan plan}
           {:op "queue-request" :plan plan :request request}])))))

(defn reset-steps
  [command now-ms]
  (let [payload (domain/data-map command)
        request {:type "eyeHeadTracking.requestReset"
                 :agency "gaze"
                 :targetAgency "eyeHeadTracking"
                 :requestId (request-id "gaze:reset" now-ms)
                 :durationMs (domain/number-or (:durationMs payload)
                                               (domain/number-or (:duration-ms payload) 300))
                 :eyes (if (contains? payload :eyes) (boolean (:eyes payload)) true)
                 :head (if (contains? payload :head) (boolean (:head payload)) true)
                 :requestedAt now-ms}]
    [{:op "request-reset" :request request}]))

(defn cancel-steps
  [command now-ms]
  (let [payload (domain/data-map command)
        request {:type "eyeHeadTracking.requestCancel"
                 :agency "gaze"
                 :targetAgency "eyeHeadTracking"
                 :requestId (request-id "gaze:cancel" now-ms)
                 :reason (or (:reason payload) "cancelled")
                 :requestedAt now-ms}]
    [{:op "request-cancel" :request request}]))

(defn command-steps
  [command state now-ms]
  (let [type (command-type command)]
    (cond
      (not (contains? supported-command-types type))
      [{:op "fail"
        :reason "unsupported-command"
        :commandType type}]

      (= "configure" type)
      [{:op "apply-config" :config (:config command)}
       {:op "publish-status" :status (:status state) :reason "configured"}]

      (#{"setMode" "set-mode"} type)
      [{:op "set-mode" :mode (:mode command)}
       {:op "publish-status" :status (:status state) :reason "mode"}]

      (#{"setActive" "set-active"} type)
      [{:op "set-active" :active (:active command)}
       {:op "publish-status" :status (:status state) :reason "active"}]

      (= "enable" type)
      [{:op "set-enabled" :enabled true}
       {:op "publish-status" :status "enabled" :reason "enabled"}]

      (= "disable" type)
      [{:op "set-enabled" :enabled false}
       {:op "request-cancel"
        :request {:type "eyeHeadTracking.requestCancel"
                  :agency "gaze"
                  :targetAgency "eyeHeadTracking"
                  :requestId (request-id "gaze:disable" now-ms)
                  :reason "disabled"
                  :requestedAt now-ms}}
       {:op "publish-status" :status "disabled" :reason "disabled"}]

      (= "reset" type)
      (reset-steps command now-ms)

      (= "cancel" type)
      (cancel-steps command now-ms)

      :else
      (target-steps command state now-ms))))

(defn plan-command
  [command state now-ms]
  (let [steps (command-steps command state now-ms)]
    {:agency "gaze"
     :commandType (command-type command)
     :createdAt now-ms
     :ok (not= "fail" (:op (first steps)))
     :steps steps}))
