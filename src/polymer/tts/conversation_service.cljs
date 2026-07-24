(ns polymer.tts.conversation-service)

;; Compatibility adapter for generator-based ConversationService callers.
;; TTS side effects remain owned by the TTS agency; this adapter only correlates
;; agency facts into the promise/callback lifecycle expected by Conversation.

(def ^:private speak-timeout-ms 30000)

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

(defn default-config [config]
  (merge {:engine "azure"
          :backendUrl ""
          :voiceName ""
          :azureVoiceName ""
          :azureStyle ""
          :azureStyleDegree nil
          :rate 1
          :pitch 1
          :volume 1
          :lipsyncIntensity 1
          :jawScale 1
          :tongueScale 1
          :visualLeadMs 35
          :webSpeechDriftThresholdSec 0.06
          :azureDriftThresholdSec 0.06
          :azureCacheLimit 8
          :debug false
          :webSpeechReferenceMode "none"}
         (data-map config)))

(defn create-service
  [agencies initial-config callbacks]
  (let [config-atom (atom (default-config initial-config))
        disposed? (atom false)
        active-speech (atom nil)
        playback-reference-status (atom "unavailable")
        playback-start-listeners (atom #{})
        playback-reference-listeners (atom #{})
        voices (atom [])
        tts-agency (js-call agencies "agency" "tts")
        reference-unsubscribe (atom nil)
        event-unsubscribe (atom nil)]
    (letfn [(dispatch! [command]
              (js-call agencies "dispatch" (clj->js {:agency "tts"
                                                      :command command})))
            (reference-track []
              (or (js-call tts-agency "getPlaybackReferenceTrack") nil))
            (notify-reference-status! [status]
              (reset! playback-reference-status status)
              (js-call callbacks "onPlaybackReferenceStatusChange" status))
            (notify-reference-track! [track]
              (doseq [listener @playback-reference-listeners]
                (listener track))
              (cond
                track (notify-reference-status! "available")
                (= "available" @playback-reference-status)
                (notify-reference-status! "unavailable")))
            (clear-speech-timeout! [speech]
              (when-let [timeout-id (:timeoutId speech)]
                (js/clearTimeout timeout-id)))
            (finish-active-speech! [interrupted?]
              (when-let [speech @active-speech]
                (when-not (:settled? speech)
                  (clear-speech-timeout! speech)
                  (reset! active-speech nil)
                  ((:resolve speech) #js {:interrupted interrupted?}))))
            (fail-active-speech! [error]
              (when-let [speech @active-speech]
                (when-not (:settled? speech)
                  (clear-speech-timeout! speech)
                  (reset! active-speech nil)
                  ((:reject speech) error))))
            (configure! []
              (dispatch! {:type "configure" :config @config-atom}))
            (handle-event! [event]
              (let [payload (data-map event)]
                (when (= "tts" (:agency payload))
                  (case (:type payload)
                    "ttsVoicesLoaded"
                    (reset! voices (:voices payload))

                    "ttsSpeechStarted"
                    (when (and @active-speech
                               (= (:name @active-speech) (:name payload)))
                      (js-call callbacks "onStart")
                      (doseq [listener @playback-start-listeners]
                        (listener)))

                    "ttsWordBoundary"
                    (when @active-speech
                      (js-call callbacks "onBoundary"
                               #js {:word (:word payload)
                                    :charIndex (or (:wordIndex payload) 0)}))

                    "ttsSpeechEnded"
                    (when @active-speech
                      (js-call callbacks "onEnd")
                      (finish-active-speech! false))

                    "ttsSpeechStopped"
                    (when (and @active-speech
                               (not= "completed" (:reason payload)))
                      (finish-active-speech! true))

                    "ttsPlaybackReferenceChanged"
                    (notify-reference-track! (reference-track))

                    "error"
                    (let [error (js/Error. (str (or (:message payload) "Polymer TTS failed")))]
                      (js-call callbacks "onError" error)
                      (fail-active-speech! error))

                    nil))))
            (stop! []
              (when-not @disposed?
                (dispatch! {:type "stop"})
                (finish-active-speech! true)))]
      (reset! event-unsubscribe (js-call agencies "subscribeEvents" handle-event!))
      (when-let [unsubscribe (js-call tts-agency "onPlaybackReferenceTrackChange"
                                      (fn [track]
                                        (notify-reference-track! track)))]
        (reset! reference-unsubscribe unsubscribe))
      (configure!)
      (let [service #js {}]
        (aset service "getVoices" (fn [] (clj->js @voices)))
        (aset service "setVoice" (fn [voice-name]
                       (swap! config-atom
                              (fn [config]
                                (cond-> (assoc config :voiceName voice-name)
                                  (= "azure" (:engine config))
                                  (assoc :azureVoiceName voice-name))))
                       (configure!)
                       true))
        (aset service "speak" (fn [text]
                    (if @disposed?
                      (js/Promise.reject (js/Error. "Polymer conversation TTS service is disposed"))
                      (let [utterance (.trim (str text))]
                        (if (zero? (count utterance))
                          (promise-resolve #js {:interrupted false})
                          (do
                            (when @active-speech
                              (dispatch! {:type "stop"})
                              (finish-active-speech! true))
                            ;; The host TTS drawer can configure this shared
                            ;; agency. Reapply the module config immediately
                            ;; before speak so its character voice wins.
                            (configure!)
                            (let [config @config-atom
                                  engine (or (:engine config) "azure")
                                  voice-name (if (= "azure" engine)
                                               (or (:azureVoiceName config) (:voiceName config))
                                               (:voiceName config))
                                  name (str "conversation:" engine ":" (.now js/Date))]
                              (js/Promise.
                               (fn [resolve reject]
                                 (let [timeout-id
                                       (js/setTimeout
                                        (fn []
                                          (when (and @active-speech
                                                     (= name (:name @active-speech)))
                                            (dispatch! {:type "stop"})
                                            (fail-active-speech!
                                             (js/Error.
                                              (str "TTS speak timed out after "
                                                   speak-timeout-ms
                                                   "ms ("
                                                   engine
                                                   ")")))))
                                        speak-timeout-ms)]
                                   (reset! active-speech {:name name
                                                          :resolve resolve
                                                          :reject reject
                                                          :settled? false
                                                          :timeoutId timeout-id})
                                   (dispatch! {:type "speak"
                                               :text utterance
                                               :engine engine
                                               :name name
                                               :backendUrl (:backendUrl config)
                                               :voiceName voice-name
                                               :style (:azureStyle config)
                                               :rate (:rate config)
                                               :pitch (:pitch config)
                                               :volume (:volume config)
                                               :visualLeadMs (:visualLeadMs config)})))))))))))
        (aset service "stop" stop!)
        (aset service "updateConfig" (fn [next-config]
                           (swap! config-atom merge (data-map next-config))
                           (configure!)
                           (when (= "none" (:webSpeechReferenceMode (data-map next-config)))
                             (notify-reference-track! nil)
                             (notify-reference-status! "unavailable"))))
        (aset service "dispose" (fn []
                      (when-not @disposed?
                        (reset! disposed? true)
                        (dispatch! {:type "stop"})
                        (when-let [unsubscribe @event-unsubscribe]
                          (unsubscribe))
                        (when-let [unsubscribe @reference-unsubscribe]
                          (unsubscribe))
                        (finish-active-speech! true)
                        (reset! playback-start-listeners #{})
                        (reset! playback-reference-listeners #{}))))
        (aset service "getPlaybackReferenceTrack" reference-track)
        (aset service "getPlaybackReferenceStatus" (fn [] @playback-reference-status))
        (aset service "preparePlaybackReference"
           (fn []
             (let [prepare (js-call tts-agency "preparePlaybackReference")]
               (if-not prepare
                 (do
                   (notify-reference-status! "unavailable")
                   (promise-resolve "unavailable"))
                 (do
                   (when (and (= "webSpeech" (:engine @config-atom))
                              (= "displayMedia" (:webSpeechReferenceMode @config-atom)))
                     (notify-reference-status! "requesting"))
                   (-> (promise-resolve prepare)
                       (.then (fn [status]
                                (notify-reference-status! status)
                                (notify-reference-track! (reference-track))
                                status))))))))
        (aset service "onPlaybackReferenceTrackChange"
           (fn [listener]
             (swap! playback-reference-listeners conj listener)
             (listener (reference-track))
             (fn []
               (swap! playback-reference-listeners disj listener))))
        (aset service "onPlaybackStart"
           (fn [listener]
             (swap! playback-start-listeners conj listener)
             (fn []
               (swap! playback-start-listeners disj listener))))
        service))))

(defn createConversationTTSService
  ([agencies] (create-service agencies nil nil))
  ([agencies config] (create-service agencies config nil))
  ([agencies config callbacks] (create-service agencies config callbacks)))
