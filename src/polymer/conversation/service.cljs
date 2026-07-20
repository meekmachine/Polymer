(ns polymer.conversation.service
  (:require [polymer.conversation.agency :as conversation-agency]))

;; Compatibility adapter for LoomLarge's old generator-based JavaScript API.
;; This is not the Conversation agency implementation. It exists so current
;; callers can keep their historical service shape while the agency owns
;; conversation state, planning, scheduling, and stream facts.

(defn data-map [value]
  (cond
    (map? value) value
    value (js->clj value :keywordize-keys true)
    :else {}))

(defn js-call [target method-name & args]
  (when target
    (when-let [method (aget target method-name)]
      (when (fn? method)
        (.apply method target (to-array args))))))

(defn promise-resolve [value]
  (.resolve js/Promise value))

(defn now-ms []
  (.now js/Date))

(defn default-config [config]
  (merge {:autoListen true
          :detectInterruptions true
          :minSpeakTime 500
          :allowTranscriptInterruptionFallback false
          :eyeHeadTracking nil
          :prosodicService nil}
         (data-map config)))

(defn generator-next [flow input has-input?]
  (if has-input?
    (.next flow input)
    (.next flow)))

(defn result-value [result]
  (aget result "value"))

(defn result-done? [result]
  (boolean (aget result "done")))

(defn create-service [tts transcription config callbacks]
  (let [config-atom (atom (default-config config))
        context (atom {:state "idle"
                       :lastUserSpeech nil
                       :lastAgentSpeech nil
                       :isInterrupted false
                       :speakStartTime nil})
        agency (conversation-agency/create-conversation-agency (clj->js @config-atom))
        flow-atom (atom nil)
        disposed? (atom false)
        unsubscribers (atom [])]
    (letfn [(dispatch! [command]
              (js-call agency "dispatch" (clj->js command)))
            (set-state! [state]
              (swap! context assoc :state state)
              (js-call callbacks "onStateChange" state))
            (eye-head []
              (:eyeHeadTracking @config-atom))
            (set-speaking! [value]
              (when-let [service (eye-head)]
                (js-call service "setSpeaking" value)))
            (set-listening! [value]
              (when-let [service (eye-head)]
                (js-call service "setListening" value)))
            (notify-error! [error]
              (js-call callbacks "onError" error))
            (start-listening! []
              (when-not @disposed?
                (set-speaking! false)
                (set-listening! true)
                (set-state! "userSpeaking")
                (js-call transcription "startListening")))
            (stop-listening! []
              (set-listening! false)
              (js-call transcription "stopListening"))
            (finish-agent-speech! []
              (set-speaking! false)
              (js-call transcription "notifyAgentSpeechEnd")
              (if (:autoListen @config-atom)
                (start-listening!)
                (set-state! "idle")))
            (speak-agent-text! [text]
              (let [utterance (str text)]
                (swap! context assoc
                       :lastAgentSpeech utterance
                       :speakStartTime (now-ms)
                       :isInterrupted false)
                (dispatch! {:type "agentUtterance"
                            :text utterance
                            :source "conversationService"})
                (js-call callbacks "onAgentUtterance" utterance)
                (js-call transcription "prepareAgentSpeech" utterance)
                (set-listening! false)
                (set-speaking! true)
                (set-state! "agentSpeaking")
                (-> (promise-resolve (js-call tts "speak" utterance))
                    (.then (fn [_result]
                             (when-not @disposed?
                               (finish-agent-speech!)))
                           (fn [error]
                             (when-not @disposed?
                               (set-speaking! false)
                               (set-state! "idle")
                               (notify-error! error)))))))
            (handle-yield! [yielded]
              (-> (promise-resolve yielded)
                  (.then (fn [value]
                           (when-not @disposed?
                             (if (and (string? value) (pos? (count value)))
                               (speak-agent-text! value)
                               (start-listening!))))
                         (fn [error]
                           (when-not @disposed?
                             (set-state! "idle")
                             (notify-error! error))))))
            (run-flow! [input has-input?]
              (when-let [flow @flow-atom]
                (try
                  (set-state! "processing")
                  (let [result (generator-next flow input has-input?)]
                    (if (result-done? result)
                      (set-state! "idle")
                      (handle-yield! (result-value result))))
                  (catch :default error
                    (set-state! "idle")
                    (notify-error! error)))))
            (submit-user-input! [text]
              (when-not @disposed?
                (let [utterance (str text)]
                  (swap! context assoc :lastUserSpeech utterance)
                  (dispatch! {:type "transcriptFinal"
                              :text utterance
                              :source "conversationService"})
                  (stop-listening!)
                  (run-flow! utterance true))))
            (transcript-handler [text is-final]
              (js-call callbacks "onUserSpeech" text is-final false)
              (when is-final
                (submit-user-input! text)))
            (interruption-handler [event]
              (when (and (:detectInterruptions @config-atom)
                         (= "agentSpeaking" (:state @context))
                         (>= (- (now-ms) (or (:speakStartTime @context) 0))
                             (:minSpeakTime @config-atom)))
                (swap! context assoc :isInterrupted true)
                (dispatch! {:type "interrupt" :reason "userSpeech" :event (data-map event)})
                (js-call tts "stop")
                (set-speaking! false)
                (set-state! "interrupted")
                (js-call callbacks "onUserSpeech" "" true true)
                (start-listening!)))]
      (when-let [unsubscribe (js-call transcription "onTranscript" transcript-handler)]
        (swap! unsubscribers conj unsubscribe))
      (when-let [unsubscribe (js-call transcription "onInterruption" interruption-handler)]
        (swap! unsubscribers conj unsubscribe))
      (when-let [unsubscribe (js-call tts "onPlaybackReferenceTrackChange"
                                      (fn [track]
                                        (js-call transcription "setAgentAudioReferenceTrack" track)))]
        (swap! unsubscribers conj unsubscribe))
      #js {:start (fn [flow-generator]
                    (when-not @disposed?
                      (try
                        (js-call tts "stop")
                        (js-call transcription "stopListening")
                        (reset! flow-atom (flow-generator))
                        (dispatch! {:type "start"})
                        (run-flow! nil false)
                        (catch :default error
                          (notify-error! error)))))
           :stop (fn []
                   (when-not @disposed?
                     (js-call tts "stop")
                     (stop-listening!)
                     (set-speaking! false)
                     (dispatch! {:type "stop" :reason "manual"})
                     (set-state! "idle")))
           :getState (fn [] (clj->js @context))
           :submitUserInput submit-user-input!
           :addSubscriptionCleanup (fn [unsubscribe]
                                     (swap! unsubscribers conj unsubscribe))
           :receiveTranscript (fn [text is-final]
                                (transcript-handler text is-final))
           :receiveAudioInterruption interruption-handler
           :dispose (fn []
                      (when-not @disposed?
                        (reset! disposed? true)
                        (doseq [unsubscribe @unsubscribers]
                          (unsubscribe))
                        (reset! unsubscribers [])
                        (js-call tts "stop")
                        (js-call transcription "stopListening")
                        (set-speaking! false)
                        (set-listening! false)
                        (js-call agency "dispose")))})))

(defn ConversationService
  ([tts transcription] (create-service tts transcription nil nil))
  ([tts transcription config] (create-service tts transcription config nil))
  ([tts transcription config callbacks] (create-service tts transcription config callbacks)))

(defn createConversationService
  ([tts transcription] (ConversationService tts transcription nil nil))
  ([tts transcription config] (ConversationService tts transcription config nil))
  ([tts transcription config callbacks] (ConversationService tts transcription config callbacks)))
