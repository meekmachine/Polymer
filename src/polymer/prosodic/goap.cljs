(ns polymer.prosodic.goap
  (:require [polymer.prosodic.state :as state]))

;; Prosodic GOAP stays intentionally local. It answers whether a speech/blink
;; fact should become a gesture plan. It does not build snippets, mutate state,
;; or talk to Animation.

(def supported-command-types
  #{"configure"
    "speechStarted"
    "speechStopped"
    "wordBoundary"
    "blinkFast"
    "conversation.userUtterance"
    "conversation.agentUtterance"
    "conversation.requestResponse"
    "conversation.cancelRequested"
    "stop"
    "reset"})

(defn command-goal [command world]
  (let [type (:type command)
        word (state/clean-word (:word command))
        word-index (int (state/number-or (:wordIndex command) (:wordIndex world)))]
    {:type type
     :enabled (get-in world [:config :enabled])
     :speaking (:speaking world)
     :word word
     :wordIndex word-index
     :hasWord (boolean word)
     :blinkFastReady (state/blink-fast-cue-ready? world (state/now-ms))}))

(defn word-gesture-kind [goal config]
  ;; This mirrors the previous TTS-panel rhythm, but as data owned by Prosodic:
  ;; emphasis at phrase starts, a head nod mid-phrase, and a small contemplate
  ;; cue just after that. Later prosody can replace this local policy without
  ;; changing host application code.
  (let [cycle (:speechGestureEvery config)
        mod-value (mod (:wordIndex goal) cycle)]
    (cond
      (= mod-value 0) "emphasis"
      (= mod-value 3) "nod"
      (= mod-value 4) "contemplate"
      :else nil)))

(defn failure-step [goal]
  (cond
    (not (contains? supported-command-types (:type goal)))
    {:op "fail" :reason "unsupported-command" :commandType (:type goal)}

    (not (:enabled goal))
    {:op "ignore" :reason "disabled"}

    (and (= "wordBoundary" (:type goal)) (not (:hasWord goal)))
    {:op "ignore" :reason "missing-word"}

    (and (= "blinkFast" (:type goal)) (not (:blinkFastReady goal)))
    {:op "ignore" :reason "cooldown"}

    :else nil))

(defn command-steps [goal config]
  (if-let [failure (failure-step goal)]
    [failure]
    (case (:type goal)
      "configure" [{:op "apply-config"}]
      "speechStarted" [{:op "record-speech-start"}]
      "speechStopped" [{:op "remove-active-gestures"}
                       {:op "record-speech-stop"}]
      "stop" [{:op "remove-active-gestures"}
              {:op "record-speech-stop"}]
      "reset" [{:op "remove-active-gestures"}
               {:op "reset-state"}]
      "blinkFast" [{:op "record-blink-fast-cue"}
                   {:op "build-gesture-snippet" :gesture "blink-fast"}
                   {:op "schedule-animation"}]
      ("conversation.userUtterance"
       "conversation.agentUtterance"
       "conversation.requestResponse")
      [{:op "record-conversation-fact"}]
      "conversation.cancelRequested" [{:op "record-conversation-fact"}
                                      {:op "remove-active-gestures"}]
      "wordBoundary" (if-let [gesture (word-gesture-kind goal config)]
                       [{:op "record-word-boundary"}
                        {:op "build-gesture-snippet" :gesture gesture}
                        {:op "schedule-animation"}]
                       [{:op "record-word-boundary"}
                        {:op "ignore" :reason "no-gesture-for-word"}]))))

(defn plan-ok? [steps]
  (not= "fail" (:op (first steps))))

(defn scheduled-gesture [steps]
  (:gesture (some #(when (= "build-gesture-snippet" (:op %)) %) steps)))

(defn plan-command [command world]
  (let [goal (command-goal command world)
        steps (command-steps goal (:config world))]
    {:goal goal
     :steps steps
     :ok (plan-ok? steps)
     :gesture (scheduled-gesture steps)}))
