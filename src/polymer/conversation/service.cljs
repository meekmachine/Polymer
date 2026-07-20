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
        playback-unsub (atom nil)]
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
            (finish-agent-speech! []
              (clear-playback-unsub!)
              (set-speaking! false)
              (js-call (prosodic) "stopTalking")
              (js-call transcription "notifyAgentSpeechEnd")
              (if (:autoListen @config-atom)
                (start-listening!)
                (set-state! "idle")))
            (speak-agent-text! [text]
              (let [utterance (str text)]
                (swap! context assoc
                       :lastAgentSpeech utterance
                       :speakStartTime nil
                       :isInterrupted false)
                (dispatch! {:type "agentUtterance"
                            :text utterance
                            :source "conversationService"})
                (js-call callbacks "onAgentUtterance" utterance)
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
                                                                   (js-call transcription "notifyAgentSpeech" utterance))))]
                                 (reset! playback-unsub unsubscribe))
                               (js-call tts "speak" utterance))))
                    (.then (fn [_result]
                             (when-not @disposed?
                               (finish-agent-speech!)))
                           (fn [error]
                             (when-not @disposed?
                               (clear-playback-unsub!)
                               (set-speaking! false)
                               (js-call (prosodic) "stopTalking")
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
                (js-call (prosodic) "stopTalking")
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
                     (clear-playback-unsub!)
                     (js-call tts "stop")
                     (stop-listening!)
                     (set-speaking! false)
                     (js-call (prosodic) "stopTalking")
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
                        (js-call tts "stop")
                        (js-call transcription "stopListening")
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
