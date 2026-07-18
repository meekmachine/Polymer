(ns polymer.lipsync.goap)

;; LipSync GOAP is deliberately small and data-only. It answers one question:
;; given this LipSync command and the current agency facts, which deterministic
;; mouth-animation steps should run, or why should the command stop before
;; changing state/scheduling animation?

(def supported-command-types
  ;; Keep the accepted command vocabulary in one place so a command can be
  ;; rejected before state mutation, scheduling, or drift correction starts.
  #{"configure"
    "startText"
    "startTimeline"
    "processAzureVisemes"
    "wordBoundary"
    "audioStarted"
    "audioTime"
    "updateWordTimings"
    "stop"
    "reset"})

(defn present-string?
  [value]
  (and (string? value) (pos? (count value))))

(defn has-visemes?
  [value]
  (boolean (seq value)))

(defn command-goal
  "Normalize an incoming LipSync command into a planner goal."
  [command world]
  ;; The goal is intentionally just facts. It should stay cheap to inspect in
  ;; tests/devtools and should never close over JS handles, timers, audio, or
  ;; engine objects.
  (let [type (:type command)
        timeline (:timeline command)]
    {:type type
     :speaking (:speaking world)
     :hasText (present-string? (:text command))
     :hasTimelineVisemes (has-visemes? (:visemes timeline))
     :hasProviderVisemes (has-visemes? (:visemes command))
     :hasWord (present-string? (:word command))
     :hasWordTimings (has-visemes? (:wordTimings command))}))

(defn failure-step
  "Return the first reason a LipSync command is invalid, or nil when runnable."
  [goal]
  (cond
    (not (contains? supported-command-types (:type goal)))
    {:op "fail" :reason "unsupported-command" :commandType (:type goal)}

    (and (= "startText" (:type goal)) (not (:hasText goal)))
    {:op "fail" :reason "missing-text"}

    (and (= "startTimeline" (:type goal)) (not (:hasTimelineVisemes goal)))
    {:op "fail" :reason "missing-timeline-visemes"}

    (and (= "processAzureVisemes" (:type goal)) (not (:hasProviderVisemes goal)))
    {:op "fail" :reason "missing-provider-visemes"}

    (and (= "wordBoundary" (:type goal)) (not (:hasWord goal)))
    {:op "fail" :reason "missing-word"}

    :else nil))

(defn command-steps
  "Return ordered pure/domain steps for a LipSync command."
  [goal]
  ;; These steps are an audit trail and validation boundary, not a hidden
  ;; interpreter. The agency still executes explicit functions for each command.
  ;; Future inter-agency constraints can extend this data without moving runtime
  ;; side effects into the planner.
  (if-let [failure (failure-step goal)]
    [failure]
    (case (:type goal)
      "configure" [{:op "apply-config"}]
      "startText" [{:op "plan-text-visemes"}
                   {:op "build-articulated-snippet"}
                   {:op "schedule-animation"}]
      "startTimeline" [{:op "normalize-timeline"}
                       {:op "build-articulated-snippet"}
                       {:op "schedule-animation"}]
      "processAzureVisemes" [{:op "normalize-provider-visemes"}
                             {:op "map-provider-visemes"}
                             {:op "build-articulated-snippet"}
                             {:op "schedule-animation"}]
      "wordBoundary" [{:op "record-word-boundary"}
                      {:op "correct-drift-if-needed"}]
      "audioStarted" [{:op "align-to-audio-clock"}]
      "audioTime" [{:op "correct-to-audio-clock"}]
      "updateWordTimings" [{:op "normalize-word-timings"}]
      "stop" [{:op "stop-active-timeline"}]
      "reset" [{:op "reset-state"}])))

(defn plan-ok?
  [steps]
  (not= "fail" (:op (first steps))))

(defn plan-command
  "Build the auditable LipSync plan for one incoming command."
  [command world]
  (let [goal (command-goal command world)
        steps (command-steps goal)]
    {:goal goal
     :steps steps
     :ok (plan-ok? steps)}))
