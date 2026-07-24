(ns polymer.prosodic.goap
  (:require [polymer.prosodic.state :as state]))

;; Prosodic GOAP stays intentionally local. It answers whether a speech/blink
;; or conversation fact should become a gesture plan. It does not build
;; snippets, mutate state, or talk to Animation.

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
        word-index (int (state/number-or (:wordIndex command) (:wordIndex world)))
        last-conversation (:lastConversationFact world)]
    {:type type
     :enabled (get-in world [:config :enabled])
     :speaking (:speaking world)
     :word word
     :wordIndex word-index
     :hasWord (boolean word)
     :blinkFastReady (state/blink-fast-cue-ready? world (state/now-ms))
     :lastConversationType (:type last-conversation)
     :conversationSuppressed? (= "conversation.cancelRequested"
                                 (:type last-conversation))}))

(defn word-gestures [goal config]
  ;; Latticework-style cadence while speaking:
  ;; - brow emphasis every browPulseEvery words
  ;; - head nod every headPulseEvery words
  ;; Conversation facts bias intensity/kind, but requestResponse must not
  ;; starve gestures once TTS has started (speaking?=true).
  (when-not (:conversationSuppressed? goal)
    (let [idx (:wordIndex goal)
          brow-every (max 1 (int (:browPulseEvery config)))
          head-every (max 1 (int (:headPulseEvery config)))
          conv (:lastConversationType goal)
          brow? (zero? (mod idx brow-every))
          head? (zero? (mod (inc idx) head-every))]
      (cond
        (and (= "conversation.requestResponse" conv)
             (not (:speaking goal)))
        (when (or brow? (zero? (mod idx 4)))
          ["contemplate"])

        (= "conversation.userUtterance" conv)
        (cond-> []
          (or brow? (= 1 (mod idx brow-every))) (conj "emphasis")
          head? (conj "nod"))

        :else
        (cond-> []
          brow? (conj "emphasis")
          head? (conj "nod"))))))

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

(defn gesture-schedule-steps [gestures]
  (into []
        (mapcat (fn [gesture]
                  [{:op "build-gesture-snippet" :gesture gesture}
                   {:op "schedule-animation" :gesture gesture}])
                gestures)))

(defn command-steps [goal config]
  (if-let [failure (failure-step goal)]
    [failure]
    (case (:type goal)
      "configure" [{:op "apply-config"}]
      "speechStarted" (cond-> [{:op "clear-conversation-suppress"}
                               {:op "record-speech-start"}]
                        (:openingGesture config)
                        (into (gesture-schedule-steps ["emphasis"])))
      "speechStopped" [{:op "remove-active-gestures"}
                       {:op "record-speech-stop"}]
      "stop" [{:op "remove-active-gestures"}
              {:op "record-speech-stop"}]
      "reset" [{:op "remove-active-gestures"}
               {:op "reset-state"}]
      "blinkFast" (into [{:op "record-blink-fast-cue"}]
                        (gesture-schedule-steps ["blink-fast"]))
      "conversation.userUtterance"
      (into [{:op "record-conversation-fact"}]
            (gesture-schedule-steps ["contemplate"]))
      "conversation.agentUtterance"
      (into [{:op "record-conversation-fact"}]
            (gesture-schedule-steps ["nod"]))
      "conversation.requestResponse"
      [{:op "record-conversation-fact"}]
      "conversation.cancelRequested" [{:op "record-conversation-fact"}
                                      {:op "remove-active-gestures"}]
      "wordBoundary" (if-let [gestures (seq (word-gestures goal config))]
                       (into [{:op "record-word-boundary"}]
                             (gesture-schedule-steps gestures))
                       [{:op "record-word-boundary"}
                        {:op "ignore" :reason (if (:conversationSuppressed? goal)
                                                "conversation-cancelled"
                                                "no-gesture-for-word")}]))))

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
