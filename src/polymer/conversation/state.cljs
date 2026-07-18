(ns polymer.conversation.state)

;; Conversation state is the local transcript and turn-taking ledger. It does
;; not own an LLM provider, a TTS provider, host UI state, or animation. Those
;; are peer agencies or external provider boundaries that Conversation talks to
;; by publishing plain stream messages.

(def default-config
  {:autoRespond true
   :maxHistory 40
   :responseSource "conversation"
   :ttsAgency "tts"
   :interruptionMode "cancel-agent"})

(defn finite-number?
  [value]
  (and (number? value) (js/isFinite value)))

(defn number-or
  [value fallback]
  (if (finite-number? value) value fallback))

(defn clamp
  [lo hi value]
  (min hi (max lo value)))

(defn data-map
  [value]
  (cond
    (map? value) value
    value (js->clj value :keywordize-keys true)
    :else {}))

(defn text-or
  [value fallback]
  (if (and (string? value) (pos? (count value)))
    value
    fallback))

(defn normalize-config
  [config]
  (let [input (merge default-config (data-map config))]
    {:autoRespond (boolean (:autoRespond input))
     :maxHistory (int (clamp 1 200 (number-or (:maxHistory input)
                                              (:maxHistory default-config))))
     :responseSource (text-or (:responseSource input)
                              (:responseSource default-config))
     :ttsAgency (text-or (:ttsAgency input)
                         (:ttsAgency default-config))
     :interruptionMode (text-or (:interruptionMode input)
                                (:interruptionMode default-config))}))

(defn default-state
  [config]
  {:agency "conversation"
   :status "idle"
   :started false
   :turnId nil
   :history []
   :context {}
   :pendingResponse nil
   :lastUserText nil
   :lastAgentText nil
   :lastEvent nil
   :interrupted false
   :startedCount 0
   :stoppedCount 0
   :userUtteranceCount 0
   :agentUtteranceCount 0
   :responseRequestCount 0
   :ttsRequestCount 0
   :cancelCount 0
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
                                                (data-map config)))))

(defn reset-state
  [state]
  (default-state (:config state)))

(defn append-history
  [state entry]
  (let [max-history (get-in state [:config :maxHistory])
        history (conj (:history state) entry)]
    (assoc state :history (vec (take-last max-history history)))))

(defn next-turn-id
  []
  (str "conversation:" (.now js/Date) ":" (rand-int 1000000)))

(defn mark-started
  [state now-ms]
  (-> state
      (assoc :started true
             :status "listening"
             :turnId (or (:turnId state) (next-turn-id))
             :interrupted false
             :lastEvent {:type "conversation.status"
                         :status "listening"
                         :at now-ms})
      (update :startedCount inc)))

(defn mark-stopped
  [state now-ms reason]
  (-> state
      (assoc :started false
             :status "idle"
             :pendingResponse nil
             :lastEvent {:type "conversation.status"
                         :status "idle"
                         :reason reason
                         :at now-ms})
      (update :stoppedCount inc)))

(defn record-user-utterance
  [state text now-ms source]
  (let [entry {:role "user"
               :text text
               :source source
               :at now-ms
               :turnId (:turnId state)}]
    (-> state
        (assoc :status "processing"
               :lastUserText text
               :lastEvent {:type "conversation.userUtterance"
                           :text text
                           :at now-ms})
        (append-history entry)
        (update :userUtteranceCount inc))))

(defn record-response-request
  [state request]
  (-> state
      (assoc :pendingResponse request
             :lastEvent {:type "conversation.requestResponse"
                         :requestId (:requestId request)
                         :at (:requestedAt request)})
      (update :responseRequestCount inc)))

(defn record-agent-utterance
  [state text now-ms source]
  (let [entry {:role "agent"
               :text text
               :source source
               :at now-ms
               :turnId (:turnId state)}]
    (-> state
        (assoc :status "agentSpeaking"
               :pendingResponse nil
               :lastAgentText text
               :lastEvent {:type "conversation.agentUtterance"
                           :text text
                           :at now-ms})
        (append-history entry)
        (update :agentUtteranceCount inc))))

(defn record-tts-request
  [state request]
  (-> state
      (assoc :lastEvent {:type "tts.requestSpeak"
                         :requestId (:requestId request)
                         :at (:requestedAt request)})
      (update :ttsRequestCount inc)))

(defn record-cancel
  [state now-ms reason]
  (-> state
      (assoc :status "interrupted"
             :pendingResponse nil
             :interrupted true
             :lastEvent {:type "conversation.cancelRequested"
                         :reason reason
                         :at now-ms})
      (update :cancelCount inc)))
