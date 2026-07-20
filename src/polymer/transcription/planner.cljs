(ns polymer.transcription.planner
  (:require [polymer.transcription.domain :as domain]
            [polymer.transcription.state :as state]))

;; The planner turns recognition commands and provider callbacks into explicit
;; actions. Provider APIs are not called here; planned provider work is emitted
;; as data so the owning effector boundary can be attached later.

(def supported-command-types
  #{"configure"
    "start"
    "stop"
    "cancel"
    "reset"
    "providerPartial"
    "partialTranscript"
    "providerFinal"
    "finalTranscript"
    "providerError"
    "tts.status"
    "ttsSpeechStarted"
    "ttsSpeechStopped"
    "ttsSpeechEnded"})

(defn confidence
  [command]
  (state/clamp 0 1 (state/number-or (:confidence command) 1)))

(defn allowed-confidence?
  [command current-state]
  (>= (confidence command) (get-in current-state [:config :minConfidence])))

(defn retry-available?
  [current-state]
  (< (:retryCount current-state) (get-in current-state [:config :maxRetries])))

(defn failure-step
  [command current-state]
  (cond
    (not (contains? supported-command-types (:type command)))
    {:op "fail" :reason "unsupported-command" :commandType (:type command)}

    (and (#{"providerPartial" "partialTranscript" "providerFinal" "finalTranscript"} (:type command))
         (not (domain/transcript-text command)))
    {:op "ignore" :reason "empty-transcript" :commandType (:type command)}

    (and (#{"providerPartial" "partialTranscript" "providerFinal" "finalTranscript"} (:type command))
         (not (allowed-confidence? command current-state)))
    {:op "ignore" :reason "low-confidence" :commandType (:type command)}

    (and (get-in current-state [:config :agentFilteringEnabled])
         (:agentSpeaking current-state)
         (#{"providerPartial" "partialTranscript" "providerFinal" "finalTranscript"} (:type command))
         (domain/agent-source? command))
    {:op "ignore" :reason "agent-speech" :commandType (:type command)}))

(defn tts-status-fact
  [command now-ms]
  (let [status (or (:status command)
                   (:stateStatus command)
                   (get-in command [:state :status])
                   (case (:type command)
                     "ttsSpeechStarted" "speaking"
                     ("ttsSpeechStopped" "ttsSpeechEnded") "idle"
                     "idle"))]
    {:type "transcription.ttsStatus"
     :agency "transcription"
     :sourceAgency "tts"
     :status status
     :speaking (or (:speaking command)
                   (= "speaking" status)
                   (= "ttsSpeechStarted" (:type command)))
     :ttsEventType (:type command)
     :at now-ms}))

(defn command-steps
  [command current-state now-ms]
  (if-let [failure (failure-step command current-state)]
    [failure]
    (case (:type command)
      "configure" [{:op "apply-config"}
                   {:op "publish-status" :status (:status current-state)}]
      "start" [{:op "start-session"}
               {:op "request-provider-start"}]
      ("stop" "cancel") [{:op "stop-session"
                          :reason (or (:reason command) (:type command))}
                         {:op "request-provider-stop"
                          :reason (or (:reason command) (:type command))}]
      "reset" [{:op "reset-state"}]
      ("providerPartial" "partialTranscript")
      (let [interrupt? (and (:agentSpeaking current-state)
                            (get-in current-state [:config :interruptDetectionEnabled])
                            (not (domain/agent-source? command)))]
        (cond-> []
          interrupt?
          (conj {:op "publish-interruption"
                 :text (domain/transcript-text command)
                 :confidence (confidence command)
                 :source (or (:source command) "provider")})

          true
          (conj {:op "publish-partial"
                 :text (domain/transcript-text command)
                 :confidence (confidence command)
                 :source (or (:source command) "provider")})))
      ("providerFinal" "finalTranscript")
      (let [interrupt? (and (:agentSpeaking current-state)
                            (get-in current-state [:config :interruptDetectionEnabled])
                            (not (domain/agent-source? command)))]
        (cond-> []
          interrupt?
          (conj {:op "publish-interruption"
                 :text (domain/transcript-text command)
                 :confidence (confidence command)
                 :source (or (:source command) "provider")})

          true
          (conj {:op "publish-final"
                 :text (domain/transcript-text command)
                 :confidence (confidence command)
                 :source (or (:source command) "provider")})))
      "providerError"
      (let [message (or (:message command) (:error command) "provider-error")
            retry? (and (:active current-state)
                        (:continuous (:config current-state))
                        (retry-available? current-state))]
        (cond-> [{:op "record-error"
                  :message message
                  :retry retry?}]
          retry? (conj {:op "request-provider-retry"
                        :message message
                        :retryAfterMs (get-in current-state [:config :retryDelayMs])})))
      ("tts.status" "ttsSpeechStarted" "ttsSpeechStopped" "ttsSpeechEnded")
      [{:op "record-tts-status"
        :fact (tts-status-fact command now-ms)}])))

(defn plan-command
  [command current-state now-ms]
  (let [steps (command-steps command current-state now-ms)]
    {:agency "transcription"
     :commandType (:type command)
     :createdAt now-ms
     :ok (not (#{"fail"} (:op (first steps))))
     :steps steps}))
