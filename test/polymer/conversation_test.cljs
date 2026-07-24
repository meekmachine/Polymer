(ns polymer.conversation-test
  (:require [cljs.test :refer [async deftest is testing]]
            [polymer.conversation.agency :as conversation]
            [polymer.conversation.service :as conversation-service]))

(defn collect-events
  [agency]
  (let [events (atom [])]
    {:events events
     :unsubscribe (.subscribeEvents ^js agency
                                    #(swap! events conj (js->clj % :keywordize-keys true)))}))

(deftest conversation-normalizes-config
  (let [agency (conversation/create-conversation-agency #js {:maxHistory 999
                                                            :autoRespond false})
        snapshot (js->clj (.snapshot ^js agency) :keywordize-keys true)]
    (is (= 200 (get-in snapshot [:config :maxHistory])))
    (is (false? (get-in snapshot [:config :autoRespond])))
    (.dispose ^js agency)))

(deftest start-and-stop-update-turn-state
  (let [agency (conversation/create-conversation-agency nil)
        events (collect-events agency)]
    (.start ^js agency)
    (let [started (js->clj (.snapshot ^js agency) :keywordize-keys true)]
      (is (:started started))
      (is (= "listening" (:status started)))
      (is (some #(and (= "conversation.status" (:type %))
                      (= "listening" (:status %)))
                @(:events events))))
    (.stop ^js agency)
    (let [stopped (js->clj (.snapshot ^js agency) :keywordize-keys true)]
      (is (false? (:started stopped)))
      (is (= "idle" (:status stopped))))
    ((:unsubscribe events))
    (.dispose ^js agency)))

(deftest final-transcript-emits-response-and-tts-requests
  (let [agency (conversation/create-conversation-agency nil)
        events (collect-events agency)]
    (.start ^js agency)
    (.dispatch ^js agency #js {:type "transcriptFinal"
                               :text "hello there"
                               :responseText "hello back"})
    (let [snapshot (js->clj (.snapshot ^js agency) :keywordize-keys true)
          response-request (some #(when (= "conversation.requestResponse" (:type %)) %)
                                 @(:events events))
          tts-request (some #(when (= "tts.requestSpeak" (:type %)) %)
                            @(:events events))]
      (is response-request)
      (is tts-request)
      (is (= "hello there" (:text response-request)))
      (is (= "hello back" (:text tts-request)))
      (is (some #(and (= "conversation.agentUtterance" (:type %))
                      (= "hello back" (:text %)))
                @(:events events)))
      (is (= 1 (:userUtteranceCount snapshot)))
      (is (= 1 (:agentUtteranceCount snapshot)))
      (is (= 1 (:ttsRequestCount snapshot))))
    ((:unsubscribe events))
    (.dispose ^js agency)))

(deftest auto-respond-can-record-user-text-without-requesting-provider
  (let [agency (conversation/create-conversation-agency #js {:autoRespond false})
        events (collect-events agency)]
    (.start ^js agency)
    (.dispatch ^js agency #js {:type "transcriptFinal"
                               :text "just remember this"})
    (let [snapshot (js->clj (.snapshot ^js agency) :keywordize-keys true)]
      (is (= 1 (:userUtteranceCount snapshot)))
      (is (some #(= "conversation.userUtterance" (:type %))
                @(:events events)))
      (is (not-any? #(= "conversation.requestResponse" (:type %))
                    @(:events events))))
    ((:unsubscribe events))
    (.dispose ^js agency)))

(deftest stale-response-after-interrupt-does-not-request-tts
  (let [agency (conversation/create-conversation-agency nil)
        events (collect-events agency)]
    (.start ^js agency)
    (.dispatch ^js agency #js {:type "transcriptFinal"
                               :text "hello"})
    (let [request (some #(when (= "conversation.requestResponse" (:type %)) %)
                        @(:events events))]
      (.interrupt ^js agency "barge-in")
      (.dispatch ^js agency #js {:type "responseReady"
                                 :requestId (:requestId request)
                                 :turnId (:turnId request)
                                 :text "late response"}))
    (is (some #(and (= "conversation.ignored" (:type %))
                    (= "stale-response" (:reason %)))
              @(:events events)))
    (is (not-any? #(and (= "tts.requestSpeak" (:type %))
                        (= "late response" (:text %)))
                  @(:events events)))
    ((:unsubscribe events))
    (.dispose ^js agency)))

(deftest interruption-publishes-cancel-without-host-callbacks
  (let [agency (conversation/create-conversation-agency nil)
        events (collect-events agency)]
    (.start ^js agency)
    (.interrupt ^js agency "barge-in")
    (let [snapshot (js->clj (.snapshot ^js agency) :keywordize-keys true)]
      (is (= "interrupted" (:status snapshot)))
      (is (= 1 (:cancelCount snapshot)))
      (is (some #(and (= "conversation.cancelRequested" (:type %))
                      (= "tts" (:targetAgency %))
                      (= "barge-in" (:reason %)))
                @(:events events))))
    ((:unsubscribe events))
    (.dispose ^js agency)))

(deftest disposed-conversation-stops-emitting
  (let [agency (conversation/create-conversation-agency nil)
        events (collect-events agency)]
    (.dispose ^js agency)
    (.dispatch ^js agency #js {:type "transcriptFinal"
                               :text "ignored"
                               :responseText "ignored"})
    (is (empty? @(:events events)))))

(defn fake-tts
  "TTS stub that exposes playback-start and reference-track hooks."
  [calls]
  (let [playback-start-listeners (atom #{})
        reference-listeners (atom #{})
        speak-resolve (atom nil)]
    #js {:speak (fn [text]
                  (swap! calls conj {:op "speak" :text text})
                  (js/Promise.
                   (fn [resolve _reject]
                     (reset! speak-resolve resolve)
                     (doseq [listener @playback-start-listeners]
                       (listener)))))
         :stop (fn []
                 (swap! calls conj {:op "stop"})
                 (when-let [resolve @speak-resolve]
                   (reset! speak-resolve nil)
                   (resolve #js {:interrupted true})))
         :finish (fn []
                   (when-let [resolve @speak-resolve]
                     (reset! speak-resolve nil)
                     (resolve #js {:completed true})))
         :getPlaybackReferenceTrack (fn [] nil)
         :onPlaybackStart (fn [listener]
                            (swap! playback-start-listeners conj listener)
                            (fn [] (swap! playback-start-listeners disj listener)))
         :onPlaybackReferenceTrackChange (fn [listener]
                                           (swap! reference-listeners conj listener)
                                           (listener nil)
                                           (fn [] (swap! reference-listeners disj listener)))}))

(defn fake-transcription
  "Transcription stub that records agent-speech notifications and can emit interruptions."
  [calls]
  (let [transcript-listeners (atom #{})
        interruption-listeners (atom #{})
        reference-track (atom nil)
        recognition-active? (atom false)]
    #js {:startListening (fn []
                           ;; Match the browser service contract: arming and
                           ;; transitioning to a user turn share one recognizer.
                           (when-not @recognition-active?
                             (reset! recognition-active? true)
                             (swap! calls conj {:op "startListening"}))
                           (js/Promise.resolve nil))
         :stopListening (fn []
                          (reset! recognition-active? false)
                          (swap! calls conj {:op "stopListening"}))
         :prepareAgentSpeech (fn [text]
                               (swap! calls conj {:op "prepareAgentSpeech" :text text}))
         :notifyAgentSpeech (fn [text]
                              (swap! calls conj {:op "notifyAgentSpeech" :text text}))
         :notifyAgentSpeechEnd (fn []
                                 (swap! calls conj {:op "notifyAgentSpeechEnd"}))
         :setAgentAudioReferenceTrack (fn [track]
                                        (reset! reference-track track)
                                        (swap! calls conj {:op "setAgentAudioReferenceTrack"}))
         :onTranscript (fn [listener]
                         (swap! transcript-listeners conj listener)
                         (fn [] (swap! transcript-listeners disj listener)))
         :onInterruption (fn [listener]
                           (swap! interruption-listeners conj listener)
                           (fn [] (swap! interruption-listeners disj listener)))
         :emitTranscript (fn [text final?]
                           (doseq [listener @transcript-listeners]
                             (listener text final?)))
         :emitInterruption (fn [event]
                             (doseq [listener @interruption-listeners]
                               (listener event)))
         :getReferenceTrack (fn [] @reference-track)}))

(deftest conversation-service-audio-interruption-stops-tts
  (async done
    (let [tts-calls (atom [])
          transcription-calls (atom [])
          states (atom [])
          tts (fake-tts tts-calls)
          transcription (fake-transcription transcription-calls)
          service (conversation-service/createConversationService
                   tts
                   transcription
                   #js {:autoListen true
                        :detectInterruptions true
                        :minSpeakTime 0}
                   #js {:onStateChange (fn [state] (swap! states conj state))})]
      (.start ^js service (fn []
                            #js {:next (fn
                                         ([] #js {:value "hello from agent" :done false})
                                         ([_input] #js {:value nil :done true}))}))
      (js/setTimeout
       (fn []
         (.emitInterruption ^js transcription #js {:timestamp (.now js/Date)
                                                   :microphoneLevel 0.2
                                                   :referenceLevel 0.01
                                                   :requiredLevel 0.05})
         (js/setTimeout
          (fn []
            (is (some #(= "stop" (:op %)) @tts-calls))
            (is (some #(= "interrupted" %) @states))
            (is (some #(= "notifyAgentSpeech" (:op %)) @transcription-calls))
            (.dispose ^js service)
            (done))
          50))
       50))))

(deftest conversation-service-reuses-armed-recognition-after-agent-speech
  (async done
    (let [tts-calls (atom [])
          transcription-calls (atom [])
          tts (fake-tts tts-calls)
          transcription (fake-transcription transcription-calls)
          service (conversation-service/createConversationService
                   tts
                   transcription
                   #js {:autoListen true
                        :detectInterruptions true
                        :minSpeakTime 0})]
      (.start ^js service (fn []
                            #js {:next (fn
                                         ([] #js {:value "hello from agent" :done false})
                                         ([_input] #js {:value nil :done true}))}))
      (js/setTimeout
       (fn []
         (.finish ^js tts)
         (js/setTimeout
          (fn []
            (is (= 1 (count (filter #(= "startListening" (:op %))
                                   @transcription-calls))))
            (.dispose ^js service)
            (done))
          50))
       50))))

(deftest conversation-service-default-transcript-fallback-interrupts-agent
  (async done
    (let [tts-calls (atom [])
          transcription-calls (atom [])
          speech-events (atom [])
          tts (fake-tts tts-calls)
          transcription (fake-transcription transcription-calls)
          service (conversation-service/createConversationService
                   tts
                   transcription
                   #js {:autoListen true
                        :detectInterruptions true
                        :minSpeakTime 0}
                   #js {:onUserSpeech (fn [text final? interruption?]
                                        (swap! speech-events conj
                                               {:text text
                                                :final final?
                                                :interruption interruption?}))})]
      (.start ^js service (fn []
                            #js {:next (fn
                                         ([] #js {:value "please wait while I talk" :done false})
                                         ([_input] #js {:value nil :done true}))}))
      (js/setTimeout
       (fn []
         (.emitTranscript ^js transcription "stop please" true)
         (js/setTimeout
          (fn []
            (is (some #(= "stop" (:op %)) @tts-calls))
            (is (some #(and (:interruption %) (= "stop please" (:text %))) @speech-events))
            (is (= 1 (count (filter #(and (:interruption %)
                                         (= "stop please" (:text %)))
                                   @speech-events))))
            (.dispose ^js service)
            (done))
          50))
       50))))
