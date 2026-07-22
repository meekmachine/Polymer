(ns polymer.conversation.service
  (:require [polymer.conversation.agency :as conversation-agency]))

;; Compatibility adapter for LoomLarge's old generator-based JavaScript API.
;; This is not the Conversation agency implementation. It exists so current
;; callers can keep their historical service shape while the agency owns
;; conversation state, planning, scheduling, and stream facts.
;;
;; Important: the generator (LoomLarge AI Chat) owns LLM replies. The orphan
;; agency inside this adapter must not auto-respond / wait on a missing
;; conversation-provider, or turn-taking freezes after the first user utterance.
;;
;; Interrupt parity with Latticework:
;; - Audio barge-in via transcription.onInterruption
;; - Optional transcript fallback via allowTranscriptInterruptionFallback
;; - Ignore non-interrupt finals while the agent is still speaking
;; - Sync TTS playback reference track into transcription for AEC barge-in

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

(defn promise-reject [value]
  (.reject js/Promise value))

(defn now-ms []
  (.now js/Date))

(defn service-handles [config]
  ;; Keep live JS service handles out of js->clj. Converting them to CLJ maps
  ;; breaks later aget/method calls (eye/head setSpeaking became a silent no-op).
  {:eyeHeadTracking (when config (aget config "eyeHeadTracking"))
   :prosodicService (when config (aget config "prosodicService"))})

(defn default-config [config]
  (let [handles (service-handles config)
        plain (dissoc (data-map config) :eyeHeadTracking :prosodicService)]
    (merge {:autoListen true
            :detectInterruptions true
            :minSpeakTime 500
            :allowTranscriptInterruptionFallback false
            :eyeHeadTracking (:eyeHeadTracking handles)
            :prosodicService (:prosodicService handles)}
           plain
           handles)))

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
        ;; Generator owns replies; disable agency autoRespond so transcriptFinal
        ;; does not emit orphaned conversation.requestResponse with no provider.
        agency (conversation-agency/create-conversation-agency
                #js {:autoRespond false
                     :maxHistory (or (:maxHistory @config-atom) 40)})
        flow-atom (atom nil)
        disposed? (atom false)
        unsubscribers (atom [])
        playback-unsub (atom nil)
        agent-speech-active? (atom false)
        pending-interrupted-transcript (atom nil)]
    (letfn [(dispatch! [command]
              (js-call agency "dispatch" (clj->js command)))
            (set-state! [state]
              (swap! context assoc :state state)
              (js-call callbacks "onStateChange" state))
            (eye-head []
              (:eyeHeadTracking @config-atom))
            (prosodic []
              (:prosodicService @config-atom))
            (set-speaking! [value]
              (when-let [service (eye-head)]
                (js-call service "setSpeaking" value)))
            (set-listening! [value]
              (when-let [service (eye-head)]
                (js-call service "setListening" value)))
            (notify-error! [error]
              (js-call callbacks "onError" error))
            (clear-playback-unsub! []
              (when-let [unsubscribe @playback-unsub]
                (unsubscribe)
                (reset! playback-unsub nil)))
            (sync-agent-audio-reference-track! []
              (let [track (or (js-call tts "getPlaybackReferenceTrack") nil)]
                (js-call transcription "setAgentAudioReferenceTrack" track)))
            (start-listening! []
              (when-not @disposed?
                (set-speaking! false)
                (set-listening! true)
                (set-state! "userSpeaking")
                (-> (promise-resolve (js-call transcription "startListening"))
                    (.catch (fn [error]
                              (when-not @disposed?
                                (notify-error! error)))))))
            (stop-listening! []
              (set-listening! false)
              (js-call transcription "stopListening"))
            (arm-interruption-listening! []
              (if (:detectInterruptions @config-atom)
                (-> (promise-resolve (js-call transcription "startListening"))
                    (.catch (fn [error]
                              (when-not @disposed?
                                (notify-error! error)
                                nil))))
                (promise-resolve nil)))
            (stop-speaking-behaviors! []
              (set-speaking! false)
              (js-call (prosodic) "stopTalking"))
            (can-interrupt-agent? []
              (and (:detectInterruptions @config-atom)
                   (not (:isInterrupted @context))
                   (= "agentSpeaking" (:state @context))
                   (some? (:speakStartTime @context))
                   (>= (- (now-ms) (:speakStartTime @context))
                       (:minSpeakTime @config-atom))))
            (handle-interruption! [source event]
              (when (can-interrupt-agent?)
                (js/console.log (str "[ConversationService] Handling interruption from " source))
                (swap! context assoc :isInterrupted true)
                (dispatch! {:type "interrupt"
                            :reason source
                            :event (data-map event)})
                (js-call tts "stop")
                (stop-speaking-behaviors!)
                (set-state! "interrupted")
                ;; Only surface a user-speech callback when we have text (transcript
                ;; fallback). Audio barge-in should not emit an empty final utterance.
                (let [text (str (or (:text (data-map event)) ""))]
                  (when (pos? (count text))
                    (js-call callbacks "onUserSpeech" text true true)))))
            (finish-agent-speech! []
              (clear-playback-unsub!)
              (reset! agent-speech-active? false)
              (stop-speaking-behaviors!)
              (js-call transcription "notifyAgentSpeechEnd")
              (if-let [pending @pending-interrupted-transcript]
                (do
                  (reset! pending-interrupted-transcript nil)
                  (submit-user-input! pending))
                (cond
                  (:isInterrupted @context)
                  (start-listening!)

                  (:autoListen @config-atom)
                  (start-listening!)

                  :else
                  (set-state! "idle"))))
            (speak-agent-text! [text]
              (let [utterance (str text)]
                (reset! agent-speech-active? true)
                (reset! pending-interrupted-transcript nil)
                (swap! context assoc
                       :lastAgentSpeech utterance
                       :speakStartTime nil
                       :isInterrupted false)
                (dispatch! {:type "agentUtterance"
                            :text utterance
                            :source "conversationService"})
                (js-call callbacks "onAgentUtterance" utterance)
                (sync-agent-audio-reference-track!)
                (js-call transcription "prepareAgentSpeech" utterance)
                (set-listening! false)
                (set-speaking! true)
                (when-let [service (eye-head)]
                  (js-call service "setGazeTarget" #js {:x 0 :y 0 :z 0}))
                (js-call (prosodic) "startTalking")
                (set-state! "agentSpeaking")
                (-> (arm-interruption-listening!)
                    (.then (fn [_]
                             (when-not @disposed?
                               (clear-playback-unsub!)
                               (when-let [unsubscribe (js-call tts "onPlaybackStart"
                                                               (fn []
                                                                 (when-not @disposed?
                                                                   (swap! context assoc :speakStartTime (now-ms))
                                                                   (sync-agent-audio-reference-track!)
                                                                   (js-call transcription "notifyAgentSpeech" utterance))))]
                                 (reset! playback-unsub unsubscribe))
                               (js-call tts "speak" utterance))))
                    (.then (fn [_result]
                             (when-not @disposed?
                               (finish-agent-speech!)))
                           (fn [error]
                             (when-not @disposed?
                               (clear-playback-unsub!)
                               (reset! agent-speech-active? false)
                               (stop-speaking-behaviors!)
                               (js-call transcription "notifyAgentSpeechEnd")
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
              (when (and (:allowTranscriptInterruptionFallback @config-atom)
                         (can-interrupt-agent?))
                (handle-interruption! "transcript" #js {:source "transcript"
                                                        :timestamp (now-ms)
                                                        :text text}))
              (let [is-interruption (or (:isInterrupted @context)
                                        (= "interrupted" (:state @context)))]
                (js-call callbacks "onUserSpeech" text is-final is-interruption)
                (when is-final
                  (cond
                    @agent-speech-active?
                    (if is-interruption
                      (reset! pending-interrupted-transcript text)
                      (js/console.debug "[ConversationService] Ignoring final transcript during uninterrupted agent speech:" text))

                    :else
                    (submit-user-input! text)))))
            (interruption-handler [event]
              (handle-interruption! "audio" event))]
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
                     (clear-playback-unsub!)
                     (reset! agent-speech-active? false)
                     (reset! pending-interrupted-transcript nil)
                     (js-call tts "stop")
                     (stop-listening!)
                     (js-call transcription "notifyAgentSpeechEnd")
                     (stop-speaking-behaviors!)
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
                        (clear-playback-unsub!)
                        (doseq [unsubscribe @unsubscribers]
                          (unsubscribe))
                        (reset! unsubscribers [])
                        (reset! agent-speech-active? false)
                        (reset! pending-interrupted-transcript nil)
                        (js-call tts "stop")
                        (js-call transcription "stopListening")
                        (js-call transcription "notifyAgentSpeechEnd")
                        (set-speaking! false)
                        (set-listening! false)
                        (js-call (prosodic) "stopTalking")
                        (js-call agency "dispose")))})))

(defn ConversationService
  ([tts transcription] (create-service tts transcription nil nil))
  ([tts transcription config] (create-service tts transcription config nil))
  ([tts transcription config callbacks] (create-service tts transcription config callbacks)))

(defn createConversationService
  ([tts transcription] (ConversationService tts transcription nil nil))
  ([tts transcription config] (ConversationService tts transcription config nil))
  ([tts transcription config callbacks] (ConversationService tts transcription config callbacks)))
