(ns polymer.transcription.state
  (:require [polymer.transcription.domain :as domain]))

;; Transcription state tracks recognition session lifecycle and ordered transcript
;; facts. Browser APIs such as Web Speech, microphone access, and interruption
;; audio analysis are effectors outside these pure state transitions.

(def default-config
  {:provider "webSpeech"
   :lang "en-US"
   :continuous true
   :interimResults true
   :maxAlternatives 1
   :maxRetries 2
   :retryDelayMs 100
   :minConfidence 0
   :agentFilteringEnabled true
   :interruptDetectionEnabled true})

(defn finite-number?
  [value]
  (and (number? value) (js/isFinite value)))

(defn number-or
  [value fallback]
  (if (finite-number? value) value fallback))

(defn clamp
  [lo hi value]
  (min hi (max lo value)))

(defn normalize-config
  [config]
  (let [input (merge default-config (domain/data-map config))]
    {:provider (domain/text-or (:provider input) (:provider default-config))
     :lang (domain/text-or (:lang input) (:lang default-config))
     :continuous (boolean (:continuous input))
     :interimResults (boolean (:interimResults input))
     :maxAlternatives (int (clamp 1 10 (number-or (:maxAlternatives input)
                                                  (:maxAlternatives default-config))))
     :maxRetries (int (clamp 0 10 (number-or (:maxRetries input)
                                             (:maxRetries default-config))))
     :retryDelayMs (int (clamp 0 5000 (number-or (:retryDelayMs input)
                                                 (:retryDelayMs default-config))))
     :minConfidence (clamp 0 1 (number-or (:minConfidence input)
                                          (:minConfidence default-config)))
     :agentFilteringEnabled (boolean (:agentFilteringEnabled input))
     :interruptDetectionEnabled (boolean (:interruptDetectionEnabled input))}))

(defn default-state
  [config]
  {:agency "transcription"
   :status "idle"
   :active false
   :sessionId nil
   :sequence 0
   :currentTranscript nil
   :isFinal false
   :lastPartial nil
   :lastFinal nil
   :lastError nil
   :lastTtsEvent nil
   :agentSpeaking false
   :agentSpeechStatus "idle"
   :lastEvent nil
   :retryCount 0
   :startedCount 0
   :stoppedCount 0
   :partialCount 0
   :finalCount 0
   :errorCount 0
   :ignoredCount 0
   :interruptionCount 0
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
                                                (domain/data-map config)))))

(defn reset-state
  [state]
  (default-state (:config state)))

(defn next-session-id
  []
  (str "transcription:" (.now js/Date) ":" (rand-int 1000000)))

(defn start-session
  [state now-ms]
  (-> state
      (assoc :active true
             :status "listening"
             :sessionId (or (:sessionId state) (next-session-id))
             :lastError nil
             :lastEvent {:type "transcription.status"
                         :status "listening"
                         :at now-ms})
      (update :startedCount inc)))

(defn stop-session
  [state now-ms reason]
  (-> state
      (assoc :active false
             :status "idle"
             :currentTranscript nil
             :isFinal false
             :lastEvent {:type "transcription.status"
                         :status "idle"
                         :reason reason
                         :at now-ms})
      (update :stoppedCount inc)))

(defn next-sequence
  [state]
  (inc (:sequence state)))

(defn record-partial
  [state fact]
  (-> state
      (assoc :status "listening"
             :sequence (:sequence fact)
             :currentTranscript (:text fact)
             :isFinal false
             :lastPartial fact
             :lastEvent fact)
      (update :partialCount inc)))

(defn record-final
  [state fact]
  (-> state
      (assoc :status "listening"
             :sequence (:sequence fact)
             :currentTranscript (:text fact)
             :isFinal true
             :lastPartial nil
             :lastFinal fact
             :lastEvent fact
             :retryCount 0)
      (update :finalCount inc)))

(defn record-error
  [state error now-ms retrying?]
  (-> state
      (assoc :status (if retrying? "retrying" "error")
             :lastError error
             :lastEvent {:type "transcription.error"
                         :message error
                         :retrying retrying?
                         :at now-ms})
      (update :errorCount inc)
      (update :retryCount (fn [count] (if retrying? (inc count) count)))))

(defn record-ignored
  [state ignored]
  (-> state
      (assoc :lastEvent ignored)
      (update :ignoredCount inc)))

(defn record-interruption
  [state fact]
  (-> state
      (assoc :lastEvent fact)
      (update :interruptionCount inc)))

(defn record-tts-status
  [state fact]
  (assoc state
         :agentSpeaking (boolean (:speaking fact))
         :agentSpeechStatus (:status fact)
         :lastTtsEvent fact
         :lastEvent fact))
