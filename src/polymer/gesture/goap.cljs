(ns polymer.gesture.goap
  (:require [polymer.gesture.state :as state]))

;; Gesture planning is intentionally local. It resolves a command into gesture
;; work and scheduler requests; it does not build snippets, mutate state, or call
;; Animation/Embody directly.

(def supported-command-types
  #{"configure"
    "loadGestures"
    "playGesture"
    "playEmoji"
    "stopGesture"
    "stopAll"
    "reset"})

(defn command-gesture-id [command world]
  (case (:type command)
    "playGesture" (state/clean-string (or (:gestureId command) (:id command)))
    "stopGesture" (state/clean-string (or (:gestureId command) (:id command)))
    "playEmoji" (get (:emojiMappings world) (state/clean-string (:emoji command)))
    nil))

(defn command-goal [command world now]
  (let [type (:type command)
        gesture-id (command-gesture-id command world)
        gesture (get (:gestures world) gesture-id)]
    {:type type
     :now now
     :enabled (get-in world [:config :enabled])
     :gestureId gesture-id
     :gesture gesture
     :emoji (state/clean-string (:emoji command))
     :activeNames (state/active-names world)
     :activeGestureNames (state/active-names-for-gesture world gesture-id)
     :replaceActive (get-in world [:config :replaceActive])
     :maxActive (get-in world [:config :maxActive])
     :cooldownReady (if gesture-id
                      (state/gesture-ready? world gesture-id now)
                      true)}))

(defn gesture-has-motion? [gesture]
  (or (seq (:keyframes gesture))
      (seq (:bones gesture))))

(defn failure-step [goal]
  (cond
    (not (contains? supported-command-types (:type goal)))
    {:op "fail" :reason "unsupported-command" :commandType (:type goal)}

    (and (#{"playGesture" "playEmoji"} (:type goal))
         (not (:enabled goal)))
    {:op "ignore" :reason "disabled"}

    (and (= "playEmoji" (:type goal))
         (not (:emoji goal)))
    {:op "fail" :reason "missing-emoji"}

    (and (#{"playGesture" "playEmoji" "stopGesture"} (:type goal))
         (not (:gestureId goal)))
    {:op "fail" :reason "missing-gesture"}

    (and (#{"playGesture" "playEmoji"} (:type goal))
         (not (:gesture goal)))
    {:op "fail" :reason "unknown-gesture" :gestureId (:gestureId goal)}

    (and (#{"playGesture" "playEmoji"} (:type goal))
         (not (gesture-has-motion? (:gesture goal))))
    {:op "fail" :reason "gesture-has-no-motion" :gestureId (:gestureId goal)}

    (and (#{"playGesture" "playEmoji"} (:type goal))
         (not (:cooldownReady goal)))
    {:op "ignore" :reason "cooldown" :gestureId (:gestureId goal)}

    :else nil))

(defn replacement-steps [goal]
  (cond
    (:replaceActive goal)
    (when (seq (:activeNames goal))
      [{:op "remove-active-gestures"
        :names (:activeNames goal)
        :reason "replaced"}])

    (>= (count (:activeNames goal)) (:maxActive goal))
    [{:op "ignore" :reason "max-active"}]

    :else nil))

(defn command-steps [goal]
  (if-let [failure (failure-step goal)]
    [failure]
    (case (:type goal)
      "configure" [{:op "apply-config"}]
      "loadGestures" [{:op "load-gestures"}]
      "reset" [{:op "remove-active-gestures"
                :names (:activeNames goal)
                :reason "reset"}
               {:op "reset-state"}]
      "stopAll" [{:op "remove-active-gestures"
                  :names (:activeNames goal)
                  :reason "requested"}]
      "stopGesture" [{:op "remove-active-gestures"
                      :names (:activeGestureNames goal)
                      :reason "requested"}]
      ("playGesture" "playEmoji") (let [replacement (replacement-steps goal)]
                                    (if (= "ignore" (:op (first replacement)))
                                      replacement
                                      (vec (concat (or replacement [])
                                                   [{:op "build-gesture-snippet"
                                                     :gestureId (:gestureId goal)}
                                                    {:op "schedule-animation"}
                                                    {:op "record-schedule"}]))))
      [{:op "fail" :reason "unsupported-command" :commandType (:type goal)}])))

(defn plan-ok? [steps]
  (not= "fail" (:op (first steps))))

(defn noop? [steps]
  (= "ignore" (:op (first steps))))

(defn remove-names [steps]
  (->> steps
       (keep (fn [step]
               (when (= "remove-active-gestures" (:op step))
                 (:names step))))
       (apply concat)
       vec))

(defn remove-reason [steps]
  (or (:reason (some #(when (= "remove-active-gestures" (:op %)) %) steps))
      "requested"))

(defn plan-command [command world now]
  (let [goal (command-goal command world now)
        steps (command-steps goal)]
    {:goal goal
     :steps steps
     :ok (plan-ok? steps)
     :noop (noop? steps)
     :gestureId (:gestureId goal)
     :gesture (:gesture goal)
     :removeNames (remove-names steps)
     :removeReason (remove-reason steps)}))
