(ns polymer.camera-context.planner)

;; The first Camera Context planner is deliberately small. It validates the
;; command vocabulary and produces auditable action data; the agency and
;; scheduler still execute those actions explicitly.

(def supported-command-types
  #{"configure"
    "updateCamera"
    "publishCameraFacts"
    "invalidateStale"
    "reset"})

(defn command-goal
  [command state now-ms]
  {:type (:type command)
   :hasPublishedFact (boolean (:lastPublishedAt state))
   :stale (:stale state)
   :nowMs now-ms})

(defn failure-step
  [goal]
  (when-not (contains? supported-command-types (:type goal))
    {:op "fail"
     :reason "unsupported-command"
     :commandType (:type goal)}))

(defn command-steps
  [goal]
  (if-let [failure (failure-step goal)]
    [failure]
    (case (:type goal)
      "configure" [{:op "apply-config"}]
      "updateCamera" [{:op "stabilize/coalesce-update"}
                      {:op "publish-camera-facts"}]
      "publishCameraFacts" [{:op "publish-camera-facts"}]
      "invalidateStale" [{:op "invalidate-stale"}]
      "reset" [{:op "reset-state"}])))

(defn plan-ok?
  [steps]
  (not= "fail" (:op (first steps))))

(defn plan-command
  [command state now-ms]
  (let [goal (command-goal command state now-ms)
        steps (command-steps goal)]
    {:goal goal
     :steps steps
     :ok (plan-ok? steps)}))
