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

(defn word-gesture-kind [goal config]
  ;; Local policy: speech cadence, biased by recent conversation context.
  ;; Cancel suppresses gestures until speechStarted clears the fact.
  (when-not (:conversationSuppressed? goal)
    (let [cycle (:speechGestureEvery config)
          mod-value (mod (:wordIndex goal) cycle)
          conv (:lastConversationType goal)]
      (cond
        (and (= "conversation.userUtterance" conv)
             (or (= mod-value 0) (= mod-value 1)))
        "emphasis"

        (= "conversation.requestResponse" conv)
        (when (or (= mod-value 0) (= mod-value 4)) "contemplate")

        (= mod-value 0) "emphasis"
        (= mod-value 3) "nod"
        (= mod-value 4) "contemplate"
        :else nil))))

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
      "speechStarted" [{:op "clear-conversation-suppress"}
                       {:op "record-speech-start"}]
      "speechStopped" [{:op "remove-active-gestures"}
                       {:op "record-speech-stop"}]
      "stop" [{:op "remove-active-gestures"}
              {:op "record-speech-stop"}]
      "reset" [{:op "remove-active-gestures"}
               {:op "reset-state"}]
      "blinkFast" [{:op "record-blink-fast-cue"}
                   {:op "build-gesture-snippet" :gesture "blink-fast"}
                   {:op "schedule-animation"}]
      "conversation.userUtterance"
      [{:op "record-conversation-fact"}
       {:op "build-gesture-snippet" :gesture "contemplate"}
       {:op "schedule-animation"}]
      "conversation.agentUtterance"
      [{:op "record-conversation-fact"}
       {:op "build-gesture-snippet" :gesture "nod"}
       {:op "schedule-animation"}]
      "conversation.requestResponse"
      [{:op "record-conversation-fact"}]
      "conversation.cancelRequested" [{:op "record-conversation-fact"}
                                      {:op "remove-active-gestures"}]
      "wordBoundary" (if-let [gesture (word-gesture-kind goal config)]
                       [{:op "record-word-boundary"}
                        {:op "build-gesture-snippet" :gesture gesture}
                        {:op "schedule-animation"}]
                       [{:op "record-word-boundary"}
                        {:op "ignore" :reason (if (:conversationSuppressed? goal)
                                                "conversation-cancelled"
                                                "no-gesture-for-word")}]))))

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
