(ns polymer.character
  (:require [polymer.animation.agency :as animation]
            [polymer.blink.agency :as blink]
            [polymer.camera-context.agency :as camera-context]
            [polymer.conversation.agency :as conversation]
            [polymer.eye-head.agency :as eye-head]
            [polymer.gaze.agency :as gaze]
            [polymer.gesture.agency :as gesture]
            [polymer.hair.agency :as hair]
            [polymer.lipsync.agency :as lipsync]
            [polymer.emphatic.agency :as emphatic]
            [polymer.prosodic.agency :as prosodic]
            [polymer.stream :as stream]
            [polymer.transcription.agency :as transcription]
            [polymer.tts.agency :as tts]))

;; A character is a network of Polymer agencies.
;;
;; LoomLarge may still consume streams from legacy Latticework services during
;; the migration, but it should not consume Polymer animation events and turn
;; them into animation calls. Inside Polymer, Blink emits blink intent and fast
;; blink facts, Camera Context emits gaze facts, Transcription emits transcript
;; facts, Conversation emits speech requests, TTS emits speech timing facts,
;; LipSync emits mouth animation intent, Gesture emits arm/hand animation intent,
;; Prosodic emits cadence expression intent, Emphatic emits linguistic stress
;; intent, Hair emits hair runtime requests, and Animation talks directly to
;; Embody.

(defn create-character-agencies [config]
  (let [input-stream (stream/create-stream)
        event-stream (stream/create-stream)
        effect-stream (stream/create-stream)
        emit-input (:emit input-stream)
        emit-event (:emit event-stream)
        emit-effect (:emit effect-stream)
        animation-agency (animation/create-animation-agency (when config (aget config "animation")))
        blink-agency (blink/create-blink-agency (when config (aget config "blink")))
        gaze-agency (gaze/create-gaze-agency (when config (aget config "gaze")))
        eye-head-agency (eye-head/create-eye-head-tracking-agency (when config (aget config "eyeHeadTracking")))
        gesture-agency (gesture/create-gesture-agency (when config (aget config "gesture")))
        camera-context-agency (camera-context/create-camera-context-agency (when config (aget config "cameraContext")))
        transcription-agency (transcription/create-transcription-agency (when config (aget config "transcription")))
        conversation-agency (conversation/create-conversation-agency (when config (aget config "conversation")))
        hair-agency (hair/create-hair-agency (when config (aget config "hair")))
        tts-agency (tts/create-tts-agency (when config (aget config "tts")))
        lipsync-agency (lipsync/create-lipsync-agency (when config (aget config "lipSync")))
        prosodic-agency (prosodic/create-prosodic-agency (when config (aget config "prosodic")))
        emphatic-agency (emphatic/create-emphatic-agency (when config (aget config "emphatic")))
        ;; Optional injectable conversation-provider effector. Hosts/tests may
        ;; pass a function or {respond} object; Polymer never embeds LLM secrets.
        conversation-provider (when config (aget config "conversationProvider"))
        unsubscribers (atom [])
        disposed? (atom false)]
    (letfn [(track! [unsubscribe]
              (swap! unsubscribers conj unsubscribe))

            (invoke-conversation-provider! [event]
              (when conversation-provider
                (let [request (clj->js (dissoc event :type))
                      respond-fn (cond
                                   (fn? conversation-provider) conversation-provider
                                   (and conversation-provider
                                        (fn? (aget conversation-provider "respond")))
                                   (aget conversation-provider "respond")
                                   :else nil)
                      reply! (fn [response]
                               (when-not @disposed?
                                 (let [payload (if (map? response)
                                                 response
                                                 (js->clj response :keywordize-keys true))]
                                   (.dispatch ^js conversation-agency
                                              (clj->js {:type "responseReady"
                                                        :requestId (or (:requestId payload)
                                                                       (:requestId event))
                                                        :turnId (or (:turnId payload)
                                                                    (:turnId event))
                                                        :text (:text payload)
                                                        :source (or (:source payload)
                                                                    "conversation-provider")})))))]
                  (when respond-fn
                    (respond-fn request reply!)))))

            (schedule-animation! [source-agency snippet options]
              (.dispatch ^js animation-agency
                         (clj->js {:type "scheduleSnippet"
                                   :sourceAgency source-agency
                                   :snippet snippet
                                   :options options})))

            (remove-animation! [source-agency name]
              (.dispatch ^js animation-agency
                         (clj->js {:type "removeSnippet"
                                   :sourceAgency source-agency
                                   :name name})))

            (seek-animation! [source-agency name offset-sec]
              (.dispatch ^js animation-agency
                         (clj->js {:type "seekSnippet"
                                   :sourceAgency source-agency
                                   :name name
                                   :offsetSec offset-sec})))

            (dispatch! [message]
              (when-not @disposed?
                (let [payload (js->clj message :keywordize-keys true)]
                  ;; Input is still observable for tests/workers, but the write
                  ;; path routes to agencies directly inside Polymer.
                  (emit-input {:type "command"
                               :agency (:agency payload)
                               :message payload})
                  (case (:agency payload)
                    "blink" (.dispatch ^js blink-agency (clj->js (:command payload)))
                    "gaze" (.dispatch ^js gaze-agency (clj->js (:command payload)))
                    "eyeHeadTracking" (.dispatch ^js eye-head-agency (clj->js (:command payload)))
                    "gesture" (.dispatch ^js gesture-agency (clj->js (:command payload)))
                    "cameraContext" (.dispatch ^js camera-context-agency (clj->js (:command payload)))
                    "transcription" (.dispatch ^js transcription-agency (clj->js (:command payload)))
                    "conversation" (.dispatch ^js conversation-agency (clj->js (:command payload)))
                    "hair" (.dispatch ^js hair-agency (clj->js (:command payload)))
                    "animation" (.dispatch ^js animation-agency (clj->js (:command payload)))
                    "tts" (.dispatch ^js tts-agency (clj->js (:command payload)))
                    "lipSync" (.dispatch ^js lipsync-agency (clj->js (:command payload)))
                    "prosodic" (.dispatch ^js prosodic-agency (clj->js (:command payload)))
                    "emphatic" (.dispatch ^js emphatic-agency (clj->js (:command payload)))
                    (emit-event {:type "error"
                                 :agency (or (:agency payload) "unknown")
                                 :message "Unknown Polymer agency"})))))

            (snapshot! []
              (clj->js {:blink (js->clj (.snapshot ^js blink-agency) :keywordize-keys true)
                        :gaze (js->clj (.snapshot ^js gaze-agency) :keywordize-keys true)
                        :eyeHeadTracking (js->clj (.snapshot ^js eye-head-agency) :keywordize-keys true)
                        :gesture (js->clj (.snapshot ^js gesture-agency) :keywordize-keys true)
                        :cameraContext (js->clj (.snapshot ^js camera-context-agency) :keywordize-keys true)
                        :transcription (js->clj (.snapshot ^js transcription-agency) :keywordize-keys true)
                        :conversation (js->clj (.snapshot ^js conversation-agency) :keywordize-keys true)
                        :hair (js->clj (.snapshot ^js hair-agency) :keywordize-keys true)
                        :tts (js->clj (.snapshot ^js tts-agency) :keywordize-keys true)
                        :lipSync (js->clj (.snapshot ^js lipsync-agency) :keywordize-keys true)
                        :prosodic (js->clj (.snapshot ^js prosodic-agency) :keywordize-keys true)
                        :emphatic (js->clj (.snapshot ^js emphatic-agency) :keywordize-keys true)
                        :animation (js->clj (.snapshot ^js animation-agency) :keywordize-keys true)}))

            (route-blink-event! [event]
              (case (:type event)
                "animation.requestScheduleSnippet"
                (schedule-animation! (:agency event) (:snippet event) (:options event))

                "signal"
                (when (and (= "blink" (:agency event))
                           (= "blink-fast" (:signal event)))
                  (.dispatch ^js prosodic-agency
                             (clj->js {:type "blinkFast"
                                       :sourceAgency "blink"
                                       :plan (:plan event)})))

                nil))

            (route-gaze-event! [event]
              (case (:type event)
                ("eyeHeadTracking.requestGaze"
                 "eyeHeadTracking.requestReset"
                 "eyeHeadTracking.requestCancel")
                (.dispatch ^js eye-head-agency (clj->js event))

                nil))

            (route-eye-head-event! [event]
              (case (:type event)
                "animation.requestScheduleSnippet"
                (schedule-animation! (:agency event) (:snippet event) (:options event))

                "animation.requestRemoveSnippet"
                (.dispatch ^js animation-agency
                           (clj->js {:type "removeSnippet"
                                     :sourceAgency (:agency event)
                                     :name (:name event)}))

                nil))

            (route-gesture-event! [event]
              (case (:type event)
                "animation.requestScheduleSnippet"
                (schedule-animation! (:agency event) (:snippet event) (:options event))

                "animation.requestRemoveSnippet"
                (remove-animation! (:agency event) (:name event))

                nil))

            (route-camera-context-event! [event]
              (case (:type event)
                "camera.fact"
                (.dispatch ^js gaze-agency (clj->js event))

                "camera.stale"
                (.dispatch ^js gaze-agency (clj->js event))

                nil))

            (route-transcription-event! [event]
              (case (:type event)
                "transcription.final"
                (.dispatch ^js conversation-agency
                           (clj->js {:type "transcript.final"
                                     :text (:text event)
                                     :source "transcription"
                                     :confidence (:confidence event)
                                     :sequence (:sequence event)
                                     :sessionId (:sessionId event)
                                     :at (:at event)}))

                "transcription.interruption"
                (.dispatch ^js conversation-agency
                           (clj->js {:type "interrupt"
                                     :reason "transcription-interruption"
                                     :text (:text event)
                                     :source "transcription"}))

                nil))

            (route-conversation-event! [event]
              ;; Router only: forward conversation facts/requests. Gaze,
              ;; Prosodic, and Emphatic own local policy through planners.
              (case (:type event)
                ("conversation.userUtterance"
                 "conversation.agentUtterance")
                (let [fact (clj->js (assoc event :sourceAgency "conversation"))]
                  (.dispatch ^js prosodic-agency fact)
                  (.dispatch ^js emphatic-agency fact)
                  (.dispatch ^js gaze-agency fact))

                "conversation.requestResponse"
                (do
                  (.dispatch ^js prosodic-agency
                             (clj->js (assoc event :sourceAgency "conversation")))
                  (invoke-conversation-provider! event))

                "tts.requestSpeak"
                (.dispatch ^js tts-agency (clj->js (:command event)))

                "conversation.cancelRequested"
                (let [cancel (clj->js (assoc event :sourceAgency "conversation"))]
                  (.dispatch ^js prosodic-agency cancel)
                  (.dispatch ^js emphatic-agency cancel)
                  (.dispatch ^js gaze-agency cancel)
                  (.dispatch ^js tts-agency (clj->js {:type "stop"
                                                      :reason (:reason event)})))

                nil))

            (route-tts-event! [event]
              (case (:type event)
                "lipSync.command"
                (.dispatch ^js lipsync-agency (clj->js (:command event)))

                "ttsStatusChanged"
                (let [status (get-in event [:state :status])]
                  (.dispatch ^js conversation-agency
                             (clj->js {:type "tts.status"
                                       :status status}))
                  (.dispatch ^js transcription-agency
                             (clj->js {:type "tts.status"
                                       :status status
                                       :speaking (get-in event [:state :speaking])
                                       :state (:state event)})))

                "ttsSpeechStarted"
                (do
                  (.dispatch ^js prosodic-agency
                             (clj->js {:type "speechStarted"
                                       :sourceAgency "tts"
                                       :name (:name event)
                                       :engine (:engine event)
                                       :text (:text event)}))
                  (.dispatch ^js emphatic-agency
                             (clj->js {:type "speechStarted"
                                       :sourceAgency "tts"
                                       :name (:name event)
                                       :engine (:engine event)
                                       :text (:text event)}))
                  (.dispatch ^js transcription-agency
                             (clj->js event))
                  (.dispatch ^js conversation-agency
                             (clj->js {:type "tts.status"
                                       :status "speaking"})))

                "ttsWordBoundary"
                (let [boundary (clj->js {:type "wordBoundary"
                                         :sourceAgency "tts"
                                         :word (:word event)
                                         :wordIndex (:wordIndex event)
                                         :observedElapsedSec (:observedElapsedSec event)
                                         :hostElapsedSec (:hostElapsedSec event)})]
                  (.dispatch ^js prosodic-agency boundary)
                  (.dispatch ^js emphatic-agency boundary))

                "ttsSpeechStopped"
                (do
                  (.dispatch ^js prosodic-agency
                             (clj->js {:type "speechStopped"
                                       :sourceAgency "tts"
                                       :reason (:reason event)}))
                  (.dispatch ^js emphatic-agency
                             (clj->js {:type "speechStopped"
                                       :sourceAgency "tts"
                                       :reason (:reason event)}))
                  (.dispatch ^js transcription-agency
                             (clj->js event))
                  (.dispatch ^js conversation-agency
                             (clj->js {:type "tts.status"
                                       :status "idle"})))

                "ttsSpeechEnded"
                (do
                  (.dispatch ^js prosodic-agency
                             (clj->js {:type "speechStopped"
                                       :sourceAgency "tts"
                                       :reason "completed"}))
                  (.dispatch ^js emphatic-agency
                             (clj->js {:type "speechStopped"
                                       :sourceAgency "tts"
                                       :reason "completed"}))
                  (.dispatch ^js transcription-agency
                             (clj->js event))
                  (.dispatch ^js conversation-agency
                             (clj->js {:type "tts.status"
                                       :status "idle"})))

                nil))

            (route-lipsync-event! [event]
              (case (:type event)
                "animation.requestScheduleSnippet"
                (schedule-animation! (:agency event) (:snippet event) (:options event))

                "animation.requestRemoveSnippet"
                (remove-animation! (:agency event) (:name event))

                "animation.requestSeekSnippet"
                (seek-animation! (:agency event) (:name event) (:offsetSec event))

                nil))

            (route-prosodic-event! [event]
              (case (:type event)
                "animation.requestScheduleSnippet"
                (schedule-animation! (:agency event) (:snippet event) (:options event))

                "animation.requestRemoveSnippet"
                (remove-animation! (:agency event) (:name event))

                nil))

            (route-emphatic-event! [event]
              (case (:type event)
                "animation.requestScheduleSnippet"
                (schedule-animation! (:agency event) (:snippet event) (:options event))

                "animation.requestRemoveSnippet"
                (remove-animation! (:agency event) (:name event))

                nil))]
      ;; Fan-in agency events to one character-level event stream for tests,
      ;; workers, and future non-React observers. LoomLarge does not need to
      ;; consume this stream for Polymer animation playback.
      (track! (.subscribeEvents ^js animation-agency
                                #(emit-event (js->clj % :keywordize-keys true))))
      (track! (.subscribeEffects ^js animation-agency
                                 #(emit-effect (js->clj % :keywordize-keys true))))
      (track! (.subscribeEvents ^js blink-agency
                                (fn [event]
                                  (let [payload (js->clj event :keywordize-keys true)]
                                    (route-blink-event! payload)
                                    (emit-event payload)))))
      (track! (.subscribeEffects ^js blink-agency
                                 #(emit-effect (js->clj % :keywordize-keys true))))
      (track! (.subscribeEvents ^js gaze-agency
                                (fn [event]
                                  (let [payload (js->clj event :keywordize-keys true)]
                                    (route-gaze-event! payload)
                                    (emit-event payload)))))
      (track! (.subscribeEffects ^js gaze-agency
                                 #(emit-effect (js->clj % :keywordize-keys true))))
      (track! (.subscribeEvents ^js eye-head-agency
                                (fn [event]
                                  (let [payload (js->clj event :keywordize-keys true)]
                                    (route-eye-head-event! payload)
                                    (emit-event payload)))))
      (track! (.subscribeEffects ^js eye-head-agency
                                 #(emit-effect (js->clj % :keywordize-keys true))))
      (track! (.subscribeEvents ^js gesture-agency
                                (fn [event]
                                  (let [payload (js->clj event :keywordize-keys true)]
                                    (route-gesture-event! payload)
                                    (emit-event payload)))))
      (track! (.subscribeEffects ^js gesture-agency
                                 #(emit-effect (js->clj % :keywordize-keys true))))
      (track! (.subscribeEvents ^js camera-context-agency
                                (fn [event]
                                  (let [payload (js->clj event :keywordize-keys true)]
                                    (route-camera-context-event! payload)
                                    (emit-event payload)))))
      (track! (.subscribeEffects ^js camera-context-agency
                                 #(emit-effect (js->clj % :keywordize-keys true))))
      (track! (.subscribeEvents ^js transcription-agency
                                (fn [event]
                                  (let [payload (js->clj event :keywordize-keys true)]
                                    (route-transcription-event! payload)
                                    (emit-event payload)))))
      (track! (.subscribeEffects ^js transcription-agency
                                 #(emit-effect (js->clj % :keywordize-keys true))))
      (track! (.subscribeEvents ^js conversation-agency
                                (fn [event]
                                  (let [payload (js->clj event :keywordize-keys true)]
                                    (route-conversation-event! payload)
                                    (emit-event payload)))))
      (track! (.subscribeEffects ^js conversation-agency
                                 #(emit-effect (js->clj % :keywordize-keys true))))
      (track! (.subscribeEvents ^js hair-agency
                                #(emit-event (js->clj % :keywordize-keys true))))
      (track! (.subscribeEffects ^js hair-agency
                                 #(emit-effect (js->clj % :keywordize-keys true))))
      (track! (.subscribeEvents ^js tts-agency
                                (fn [event]
                                  (let [payload (js->clj event :keywordize-keys true)]
                                    (route-tts-event! payload)
                                    (emit-event payload)))))
      (track! (.subscribeEffects ^js tts-agency
                                 #(emit-effect (js->clj % :keywordize-keys true))))
      (track! (.subscribeEvents ^js lipsync-agency
                                (fn [event]
                                  (let [payload (js->clj event :keywordize-keys true)]
                                    (route-lipsync-event! payload)
                                    (emit-event payload)))))
      (track! (.subscribeEffects ^js lipsync-agency
                                 #(emit-effect (js->clj % :keywordize-keys true))))
      (track! (.subscribeEvents ^js prosodic-agency
                                (fn [event]
                                  (let [payload (js->clj event :keywordize-keys true)]
                                    (route-prosodic-event! payload)
                                    (emit-event payload)))))
      (track! (.subscribeEffects ^js prosodic-agency
                                 #(emit-effect (js->clj % :keywordize-keys true))))
      (track! (.subscribeEvents ^js emphatic-agency
                                (fn [event]
                                  (let [payload (js->clj event :keywordize-keys true)]
                                    (route-emphatic-event! payload)
                                    (emit-event payload)))))
      (track! (.subscribeEffects ^js emphatic-agency
                                 #(emit-effect (js->clj % :keywordize-keys true))))
      #js {:dispatch dispatch!
           :input (stream/writable-port input-stream dispatch!)
           :events (stream/readable-port event-stream)
           :effects (stream/readable-port effect-stream)
           :snapshot snapshot!
           :agency (fn [name]
                     (case name
                       "animation" animation-agency
                       "blink" blink-agency
                       "gaze" gaze-agency
                       "eyeHeadTracking" eye-head-agency
                       "gesture" gesture-agency
                       "cameraContext" camera-context-agency
                       "transcription" transcription-agency
                       "conversation" conversation-agency
                       "hair" hair-agency
                       "tts" tts-agency
                       "lipSync" lipsync-agency
                       "prosodic" prosodic-agency
                       "emphatic" emphatic-agency
                       nil))
           :subscribeInput (fn [listener] ((:subscribe input-stream) listener))
           :subscribeEvents (fn [listener] ((:subscribe event-stream) listener))
           :subscribeEffects (fn [listener] ((:subscribe effect-stream) listener))
           :subscribe (fn [listener] ((:subscribe event-stream) listener))
           :subscribeStatus (fn [listener] ((:subscribe event-stream) listener))
           :subscribeCommands (fn [listener] ((:subscribe effect-stream) listener))
           :dispose (fn []
                      (when-not @disposed?
                        (reset! disposed? true)
                        (doseq [unsubscribe @unsubscribers]
                          (unsubscribe))
                        (reset! unsubscribers [])
                        (.dispose ^js blink-agency)
                        (.dispose ^js gaze-agency)
                        (.dispose ^js eye-head-agency)
                        (.dispose ^js gesture-agency)
                        (.dispose ^js camera-context-agency)
                        (.dispose ^js transcription-agency)
                        (.dispose ^js conversation-agency)
                        (.dispose ^js hair-agency)
                        (.dispose ^js tts-agency)
                        (.dispose ^js lipsync-agency)
                        (.dispose ^js prosodic-agency)
                        (.dispose ^js emphatic-agency)
                        (.dispose ^js animation-agency)
                        ((:dispose input-stream))
                        ((:dispose event-stream))
                        ((:dispose effect-stream))))})))
