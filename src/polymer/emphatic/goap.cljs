(ns polymer.emphatic.goap
  (:require [polymer.emphatic.domain :as domain]
            [polymer.emphatic.state :as state]))

;; Emphatic Expression is content-driven: analyze utterance text, then spike
;; brow/head gestures on emphasis word boundaries during speech. Cadence
;; presence stays in Prosodic; this agency owns linguistic stress.

(def supported-command-types
  #{"configure"
    "analyzeUtterance"
    "userUtterance"
    "agentUtterance"
    "conversation.userUtterance"
    "conversation.agentUtterance"
    "conversation.cancelRequested"
    "speechStarted"
    "speechStopped"
    "wordBoundary"
    "stop"
    "reset"})

(defn utterance-text [command]
  (or (:text command) (:utterance command) ""))

(defn command-goal [command world]
  (let [type (:type command)
        word (state/clean-word (:word command))
        word-index (int (state/number-or (:wordIndex command) (:wordIndex world)))
        plan (:plan world)
        emphatic-index? (and plan (domain/emphasis-index? plan word-index))]
    {:type type
     :enabled (get-in world [:config :enabled])
     :speaking (:speaking world)
     :word word
     :wordIndex word-index
     :hasWord (boolean word)
     :text (utterance-text command)
     :hasText (pos? (count (utterance-text command)))
     :plan plan
     :emphaticIndex? emphatic-index?
     :cooldownReady (state/emphatic-cooldown-ready? world (state/now-ms))}))

(defn failure-step [goal]
  (cond
    (not (contains? supported-command-types (:type goal)))
    {:op "fail" :reason "unsupported-command" :commandType (:type goal)}

    (not (:enabled goal))
    {:op "ignore" :reason "disabled"}

    (and (= "wordBoundary" (:type goal)) (not (:hasWord goal)))
    {:op "ignore" :reason "missing-word"}

    :else nil))

(defn analysis-steps [goal]
  (when (:hasText goal)
    [{:op "analyze-utterance"}]))

(defn gesture-schedule-steps [gestures]
  (into []
        (mapcat (fn [gesture]
                  [{:op "build-gesture-snippet"
                    :gesture (or (:type gesture) "raise")
                    :channel (name (domain/gesture-channel gesture))}
                   {:op "schedule-animation"
                    :gesture (or (:type gesture) "raise")}])
                gestures)))

(defn command-steps [goal _config]
  (if-let [failure (failure-step goal)]
    [failure]
    (case (:type goal)
      "configure" [{:op "apply-config"}]

      ("analyzeUtterance" "userUtterance" "agentUtterance"
       "conversation.userUtterance" "conversation.agentUtterance")
      (or (analysis-steps goal)
          [{:op "ignore" :reason "missing-text"}])

      "speechStarted" (cond-> [{:op "record-speech-start"}]
                        (:hasText goal) (into (analysis-steps goal)))

      "speechStopped" [{:op "remove-active-gestures"}
                       {:op "record-speech-stop"}]

      "stop" [{:op "remove-active-gestures"}
              {:op "record-speech-stop"}]

      "reset" [{:op "remove-active-gestures"}
               {:op "reset-state"}]

      "conversation.cancelRequested" [{:op "remove-active-gestures"}]

      "wordBoundary"
      (cond
        (not (:emphaticIndex? goal))
        [{:op "record-word-boundary"}
         {:op "ignore" :reason "non-emphatic-word"}]

        (not (:cooldownReady goal))
        [{:op "record-word-boundary"}
         {:op "ignore" :reason "cooldown"}]

        :else
        (let [gestures (domain/gestures-for-word-index (:plan goal) (:wordIndex goal))]
          (into [{:op "record-word-boundary"}]
                (gesture-schedule-steps (or (seq gestures)
                                            [{:type "raise"}]))))))))

(defn plan-ok? [steps]
  (not= "fail" (:op (first steps))))

(defn scheduled-gestures [steps]
  (into []
        (keep #(when (= "build-gesture-snippet" (:op %)) (:gesture %)))
        steps))

(defn plan-command [command world]
  (let [goal (command-goal command world)
        steps (command-steps goal (:config world))
        gestures (scheduled-gestures steps)]
    {:goal goal
     :steps steps
     :ok (plan-ok? steps)
     :gestures gestures
     :gesture (first gestures)}))
