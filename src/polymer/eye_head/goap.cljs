(ns polymer.eye-head.goap
  (:require [polymer.eye-head.domain :as domain]
            [polymer.eye-head.snippet :as snippet]))

;; Eye/Head planning accepts gaze requests from peer agencies and turns them
;; into local movement work. Animation requests are produced only after the
;; scheduler accepts the plan.

(def supported-command-types
  #{"configure"
    "enable"
    "disable"
    "setTarget"
    "set-target"
    "requestGaze"
    "eyeHeadTracking.requestGaze"
    "reset"
    "eyeHeadTracking.requestReset"
    "cancel"
    "eyeHeadTracking.requestCancel"})

(defn command-type
  [command]
  (:type command))

(defn plan-gaze
  [command state now-ms]
  (if-not (domain/has-requested-target? command)
    [{:op "fail"
      :reason "missing-target"
      :commandType (command-type command)}]
    (let [request (domain/normalize-gaze-request command state now-ms)]
      (cond
      (not (get-in state [:config :enabled]))
      [{:op "ignore-request"
        :request request
        :reason "disabled"}]

      (not (domain/movement-enabled? request))
      [{:op "ignore-request"
        :request request
        :reason "movement-disabled"}]

      :else
      [{:op "queue-gaze"
        :request request
        :snippet (snippet/build-gaze-snippet request (:config state))}]))))

(defn plan-reset
  [command state now-ms]
  (let [request (domain/normalize-reset-request command state now-ms)]
    (if (domain/movement-enabled? request)
      [{:op "queue-reset"
        :request request
        :snippet (snippet/build-gaze-snippet request (:config state))}]
      [{:op "ignore-request"
        :request request
        :reason "movement-disabled"}])))

(defn plan-cancel
  [command now-ms]
  [{:op "cancel-active"
    :request (domain/normalize-cancel-request command now-ms)}])

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
       {:op "publish-status" :reason "configured"}]

      (= "enable" type)
      [{:op "set-enabled" :enabled true}
       {:op "publish-status" :reason "enabled"}]

      (= "disable" type)
      [{:op "set-enabled" :enabled false}
       {:op "cancel-active"
        :request (domain/normalize-cancel-request
                  (assoc command :reason "disabled")
                  now-ms)}
       {:op "publish-status" :reason "disabled"}]

      (#{"reset" "eyeHeadTracking.requestReset"} type)
      (plan-reset command state now-ms)

      (#{"cancel" "eyeHeadTracking.requestCancel"} type)
      (plan-cancel command now-ms)

      :else
      (plan-gaze command state now-ms))))

(defn plan-command
  [command state now-ms]
  (let [steps (command-steps command state now-ms)]
    {:agency "eyeHeadTracking"
     :commandType (command-type command)
     :createdAt now-ms
     :ok (not= "fail" (:op (first steps)))
     :steps steps}))
