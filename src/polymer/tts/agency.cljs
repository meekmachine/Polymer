(ns polymer.tts.agency
  (:require [polymer.stream :as stream]
            [polymer.tts.azure :as azure]
            [polymer.tts.planner :as planner]
            [polymer.tts.runtime :refer [azure-cache-key
                                          backend-url
                                          callback-event
                                          cleanup-audio!
                                          custom-provider
                                          js-error
                                          load-azure-voices!
                                          load-web-speech-voices!
                                          normalize-voices
                                          now-ms
                                          plan-failure-message
                                          play-azure-audio!
                                          prime-audio!
                                          remember-cache
                                          scalar->azure-percent
                                          speak-web-speech!
                                          speech-synthesis*
                                          synthesize-azure!
                                          stop-web-speech-handle!]]
            [polymer.tts.scheduler :as scheduler]
            [polymer.tts.state :as state]))

;; TTS owns speech-provider side effects and emits plain data facts for LipSync.
;; The impure browser/backend/audio calls live in `polymer.tts.runtime`; this
;; namespace owns command planning, session state, and callback routing.

(defn create-tts-agency
  "Create a TTS agency that owns provider sessions and emits LipSync facts."
  [config]
  (let [initial-config (js->clj config :keywordize-keys true)
        input-stream (stream/create-stream)
        event-stream (stream/create-stream)
        effect-stream (stream/create-stream)
        emit-input (:emit input-stream)
        emit-event (:emit event-stream)
        state-atom (atom (state/default-state initial-config))
        resources (atom {:audio nil
                         :audioUrl nil
                         :audioSource nil
                         :webSpeechHandle nil})
        session-scheduler (scheduler/create-scheduler)
        azure-cache (atom {})
        voice-load-seq (atom 0)
        voice-load-requests (atom {})
        disposed? (atom false)]
    (letfn [(session-id []
                ;; Every provider callback captures the session id active when
                ;; it was registered. Replacements/stops increment the token so
                ;; late callbacks cannot mutate state or emit stale LipSync data.
              (:sessionId @state-atom))

            (active-session? [id]
              (and (not @disposed?) (= id (session-id))))

            (emit-status! []
                ;; Status events are coarse facts, not animation ticks. The
                ;; agency emits word/viseme progress through streams instead of
                ;; making a host poll mutable state.
              (emit-event {:type "ttsStatusChanged"
                           :agency "tts"
                           :state (state/visible-state @state-atom)}))

            (emit-error! [message]
              (swap! state-atom state/record-error message)
              (emit-event {:type "error" :agency "tts" :message message})
              (emit-status!))

            (emit-lipsync! [command]
                ;; This is an event-stream bridge inside Polymer. The agency
                ;; system routes this command to LipSync, which then emits
                ;; animation intent for the Animation agency.
              (emit-event {:type "lipSync.command"
                           :agency "tts"
                           :command command}))

            (active-speech? []
              (contains? #{"loading" "speaking"} (:status @state-atom)))

            (next-voice-load! [engine]
              (let [request-id (swap! voice-load-seq inc)]
                (swap! voice-load-requests assoc engine request-id)
                request-id))

            (active-voice-load? [engine request-id]
              (and (not @disposed?)
                   (= request-id (get @voice-load-requests engine))))

            (cancel-voice-loads! []
              (swap! voice-load-requests empty))

            (stop-web-speech! []
              (when-let [handle (:webSpeechHandle @resources)]
                (stop-web-speech-handle! handle))
              (swap! resources assoc :webSpeechHandle nil))

            (configure-lipsync! [engine command]
                ;; TTS knows provider timing and user speech controls; LipSync knows
                ;; how those controls become mouth/jaw/tongue animation.
              (let [config (:config @state-atom)]
                (emit-lipsync! {:type "configure"
                                :config {:intensity (:lipsyncIntensity config)
                                         :jawScale (:jawScale config)
                                         :tongueScale (:tongueScale config)
                                         :speechRate (or (:rate command) (:rate config))
                                         :visualLeadMs (or (:visualLeadMs command) (:visualLeadMs config))
                                         :wordDriftThresholdSec (if (= engine "azure")
                                                                  (:azureDriftThresholdSec config)
                                                                  (:webSpeechDriftThresholdSec config))}})))

            (stop-session! [reason]
              ((:stop-all session-scheduler))
              (stop-web-speech!)
              (cleanup-audio! resources)
              (emit-lipsync! {:type "stop" :reason reason})
              (swap! state-atom state/record-stopped (now-ms))
              (emit-event {:type "ttsSpeechStopped"
                           :agency "tts"
                           :reason reason
                           :stoppedAt (:endedAt @state-atom)})
              (emit-status!))

            (finish-session! [id]
              (when (active-session? id)
                (stop-session! "completed")
                (emit-event {:type "ttsSpeechEnded"
                             :agency "tts"
                             :endedAt (:endedAt @state-atom)})))

            (word-boundary! [id word observed-elapsed-sec host-elapsed-sec]
              (when (and (active-session? id) (pos? (count (or word ""))))
                (let [word-index (:wordIndex @state-atom)]
                  (swap! state-atom state/record-word-boundary word-index)
                  (emit-lipsync! {:type "wordBoundary"
                                  :word word
                                  :wordIndex word-index
                                  :observedElapsedSec observed-elapsed-sec
                                  :hostElapsedSec host-elapsed-sec})
                  (emit-event {:type "ttsWordBoundary"
                               :agency "tts"
                               :word word
                               :wordIndex word-index
                               :observedElapsedSec observed-elapsed-sec
                               :hostElapsedSec host-elapsed-sec}))))

            (start-web-speech! [id command snippet-name]
              (let [config (:config @state-atom)
                    text (:text command)
                    plan {:text text
                          :voiceName (or (:voiceName command) (:voiceName config))
                          :rate (or (:rate command) (:rate config))
                          :pitch (or (:pitch command) (:pitch config))
                          :volume (or (:volume command) (:volume config))
                          :onAudioStarted (fn [_event]
                                            (when (active-session? id)
                                              (configure-lipsync! "webSpeech" command)
                                              (emit-lipsync! {:type "startText"
                                                              :name snippet-name
                                                              :text text
                                                              :source "webSpeech"})
                                              (emit-lipsync! {:type "audioStarted"
                                                              :name snippet-name
                                                              :audioTimeSec 0})
                                              (swap! state-atom state/record-speaking (now-ms))
                                              (emit-event {:type "ttsSpeechStarted"
                                                           :agency "tts"
                                                           :engine "webSpeech"
                                                           :name snippet-name
                                                           :startedAt (:startedAt @state-atom)})
                                              (emit-status!)))
                          :onBoundary (fn [event]
                                        (let [payload (callback-event event)]
                                          (word-boundary! id
                                                          (:word payload)
                                                          (:observedElapsedSec payload)
                                                          (:hostElapsedSec payload))))
                          :onEnd (fn [] (finish-session! id))
                          :onError (fn [error] (emit-error! (.-message error)))
                          :scheduleStartFallback
                          (fn [delay-ms callback]
                            ((:schedule-start-fallback session-scheduler) id delay-ms callback))
                          :cancelStartFallback
                          (fn []
                            ((:cancel-start-fallback session-scheduler) id))}]
                (-> (speak-web-speech! config plan)
                    (.then (fn [handle]
                             (if (active-session? id)
                               (swap! resources assoc :webSpeechHandle handle)
                               (stop-web-speech-handle! handle))))
                    (.catch (fn [error]
                              (when (active-session? id)
                                (emit-error! (.-message error))))))))

            (start-azure! [id command snippet-name]
              (let [config (:config @state-atom)
                    text (:text command)
                    voice-name (or (:voiceName command) (:azureVoiceName config))
                    style (or (:style command) (:azureStyle config))
                    rate (or (:rate command) (:rate config))
                    pitch (or (:pitch command) (:pitch config))
                    volume (or (:volume command) (:volume config))
                    cache-key (azure-cache-key text voice-name style (scalar->azure-percent rate) (scalar->azure-percent pitch))
                    cached (get @azure-cache cache-key)
                    synth-promise (if cached
                                    (js/Promise.resolve (clj->js cached))
                                    (synthesize-azure! config (assoc command
                                                                     :voiceName voice-name
                                                                     :style style
                                                                     :rate rate
                                                                     :pitch pitch)))]
                  ;; Prime synchronously while the click/user gesture is still
                  ;; available. Synthesis may finish later, but the element is
                  ;; already allowed to play in stricter browsers.
                (prime-audio! resources volume)
                (-> synth-promise
                    (.then (fn [raw]
                               ;; Provider/backend shapes are normalized before
                               ;; any playback or LipSync command is emitted.
                             (let [payload (azure/normalize-azure-synthesis (js->clj raw :keywordize-keys true))]
                               (when-not cached
                                 (swap! azure-cache remember-cache cache-key payload (:azureCacheLimit config)))
                               (if (and (:audioBase64 payload) (seq (:visemes payload)))
                                 payload
                                 (throw (js-error "Azure TTS returned no usable audio or viseme payload"))))))
                    (.then (fn [payload]
                             (when (active-session? id)
                               (play-azure-audio! config resources
                                                  (assoc payload
                                                         :volume volume)))))
                    (.then (fn [playback]
                             (when (active-session? id)
                               (let [payload (if cached cached (get @azure-cache cache-key))
                                     duration-sec (or (:durationSec payload) (aget playback "durationSec") 0)
                                     word-timings (:wordTimings payload)]
                                 (configure-lipsync! "azure" command)
                                 (emit-lipsync! (azure/azure-synthesis->lipsync-command
                                                 snippet-name
                                                 text
                                                 (assoc payload :durationSec duration-sec)
                                                 config))
                                 (emit-lipsync! {:type "audioStarted"
                                                 :name snippet-name
                                                 :audioTimeSec 0})
                                 (swap! state-atom state/record-speaking (now-ms))
                                 (emit-event {:type "ttsSpeechStarted"
                                              :agency "tts"
                                              :engine "azure"
                                              :name snippet-name
                                              :startedAt (:startedAt @state-atom)})
                                 (emit-status!)
                                 (when-let [clock (or (:clock playback)
                                                      (when (aget playback "clock")
                                                        (js->clj (aget playback "clock") :keywordize-keys true)))]
                                   ((:schedule-boundaries session-scheduler)
                                    id
                                    active-session?
                                    clock
                                    word-timings
                                    (fn [event]
                                      (word-boundary! id
                                                      (:word event)
                                                      (:observedElapsedSec event)
                                                      nil))))
                                 (when-let [audio (or (:audio playback) (aget playback "audio"))]
                                   (set! (.-onended audio) #(finish-session! id)))))))
                    (.catch (fn [error]
                              (when (active-session? id)
                                (emit-error! (.-message error))))))))

            (speak! [command]
              (let [config (:config @state-atom)
                    engine (or (:engine command) (:engine config))
                    text (:text command)
                    snippet-name (or (:name command) (str "tts_" engine "_" (.now js/Date)))
                    ;; Provider planning sees only capability facts. It does not
                    ;; receive JS handles or secrets, and it never performs side
                    ;; effects.
                    world {:backendUrl (backend-url config command)
                           :hasAzureSynthesize (boolean (custom-provider config :azureSynthesize))
                           :hasWebSpeech (boolean (or (custom-provider config :webSpeechSpeak)
                                                      (speech-synthesis*)))}
                    plan (planner/plan-speech (assoc command :engine engine) config world)]
                (swap! state-atom (fn [state]
                                    (-> state
                                        state/next-session
                                        (state/record-plan plan)
                                        (state/record-loading engine text snippet-name))))
                (emit-event {:type "ttsPlanCreated"
                             :agency "tts"
                             :plan plan})
                (emit-status!)
                (if-not (:ok plan)
                  (emit-error! (plan-failure-message plan))
                  (case engine
                    "azure" (start-azure! (session-id) command snippet-name)
                    "webSpeech" (start-web-speech! (session-id) command snippet-name)
                    (emit-error! (str "Unsupported TTS engine: " engine))))))

            (load-voices! [engine]
              (let [config (:config @state-atom)
                      ;; Voice loading has its own plan so missing backend/browser
                      ;; support is visible before any provider request is made.
                    request-id (next-voice-load! engine)
                    world {:backendUrl (:backendUrl config)
                           :hasAzureVoices (boolean (custom-provider config :azureVoices))
                           :hasWebSpeech (boolean (or (custom-provider config :webSpeechVoices)
                                                      (speech-synthesis*)))}
                    plan (planner/plan-voice-load engine world)]
                (emit-event {:type "ttsPlanCreated"
                             :agency "tts"
                             :plan plan})
                (if-not (:ok plan)
                  (do
                    (when (= "azure" engine)
                      (swap! state-atom
                             state/record-azure-status
                             "error"
                             (plan-failure-message plan)
                             []))
                    (emit-error! (plan-failure-message plan)))
                  (case engine
                    "webSpeech"
                    (-> (load-web-speech-voices! config)
                        (.then (fn [voices]
                                 (when (active-voice-load? engine request-id)
                                   (let [normalized (normalize-voices
                                                     (js->clj voices :keywordize-keys true)
                                                     "webSpeech")]
                                     (swap! state-atom state/record-web-speech-voices normalized)
                                     (emit-event {:type "ttsVoicesLoaded"
                                                  :agency "tts"
                                                  :engine "webSpeech"
                                                  :voices normalized})
                                     (emit-status!)))))
                        (.catch (fn [error]
                                  (when (active-voice-load? engine request-id)
                                    (emit-error! (.-message error))))))

                    "azure"
                    (-> (load-azure-voices! config)
                        (.then (fn [response]
                                 (when (active-voice-load? engine request-id)
                                   (let [data (js->clj response :keywordize-keys true)
                                         voices (normalize-voices (:voices data) "azure")
                                         ready? (and (:valid data) (seq voices))
                                         status (if ready? "ready" "error")
                                         message (if ready?
                                                   (str "Connected via backend environment (" (count voices) " voices).")
                                                   (or (:message data) "Backend Azure credentials are not configured."))]
                                     (swap! state-atom state/record-azure-status status message voices)
                                     (emit-event {:type "ttsVoicesLoaded"
                                                  :agency "tts"
                                                  :engine "azure"
                                                  :voices voices
                                                  :status status
                                                  :message message})
                                     (emit-status!)))))
                        (.catch (fn [error]
                                  (when (active-voice-load? engine request-id)
                                    (swap! state-atom state/record-azure-status "error" (.-message error) [])
                                    (emit-error! (.-message error))))))

                    (emit-error! (str "Unsupported voice provider: " engine))))))

            (run-action! [action]
              (case (:op action)
                "cancel-voice-loads"
                (cancel-voice-loads!)

                "configure"
                (do
                  (swap! state-atom state/configure (:config action))
                  (emit-status!))

                "load-voices"
                (load-voices! (:engine action))

                "stop-active-session"
                (when (active-speech?)
                  (stop-session! (:reason action)))

                "advance-session"
                (swap! state-atom state/next-session)

                "stop-session"
                (stop-session! (:reason action))

                "speak"
                (speak! (:command action))

                "reset"
                (do
                  (stop-web-speech!)
                  ((:stop-all session-scheduler))
                  (cleanup-audio! resources)
                  (reset! state-atom (state/default-state nil))
                  (emit-status!))

                "error"
                (emit-error! (:message action))

                (emit-error! (str "Unknown TTS planner action: " (:op action)))))

            (dispatch! [command]
              (when-not @disposed?
                (let [payload (js->clj command :keywordize-keys true)]
                  (emit-input {:type "command"
                               :agency "tts"
                               :command payload})
                  (doseq [action (planner/plan-command @state-atom payload)]
                    (run-action! action)))))]
      #js {:dispatch dispatch!
           :snapshot (fn [] (state/visible-state @state-atom))
           :schedulerQueue (fn [] (clj->js ((:queue session-scheduler))))
           :input (stream/writable-port input-stream dispatch!)
           :events (stream/readable-port event-stream)
           :effects (stream/readable-port effect-stream)
           :subscribeInput (fn [listener] ((:subscribe input-stream) listener))
           :subscribeEvents (fn [listener] ((:subscribe event-stream) listener))
           :subscribeEffects (fn [listener] ((:subscribe effect-stream) listener))
           :subscribe (fn [listener] ((:subscribe event-stream) listener))
           :subscribeStatus (fn [listener] ((:subscribe event-stream) listener))
           :subscribeCommands (fn [listener] ((:subscribe effect-stream) listener))
           :configure (fn [next-config] (dispatch! #js {:type "configure" :config next-config}))
           :loadVoices (fn
                         ([] (dispatch! #js {:type "loadVoices"}))
                         ([engine] (dispatch! #js {:type "loadVoices" :engine engine})))
           :speak (fn [text] (dispatch! #js {:type "speak" :text text}))
           :stop (fn [] (dispatch! #js {:type "stop"}))
           :reset (fn [] (dispatch! #js {:type "reset"}))
           :dispose (fn []
                      (when-not @disposed?
                        (reset! disposed? true)
                        (cancel-voice-loads!)
                        ((:dispose session-scheduler))
                        (stop-web-speech!)
                        (cleanup-audio! resources)
                        ((:dispose input-stream))
                        ((:dispose event-stream))
                        ((:dispose effect-stream))))})))
