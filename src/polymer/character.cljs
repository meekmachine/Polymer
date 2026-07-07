(ns polymer.character
  (:require [polymer.animation.agency :as animation]
            [polymer.blink.agency :as blink]
            [polymer.lipsync.agency :as lipsync]
            [polymer.prosodic.agency :as prosodic]
            [polymer.stream :as stream]
            [polymer.tts.agency :as tts]))

;; A character is a network of Polymer agencies.
;;
;; LoomLarge may still consume streams from legacy Latticework services during
;; the migration, but it should not consume Polymer animation events and turn
;; them into animation calls. Inside Polymer, Blink emits blink intent and fast
;; blink facts, TTS emits speech timing facts, LipSync emits mouth animation
;; intent, Prosodic emits speech/blink expression intent, and Animation talks
;; directly to Embody.

(defn create-character-agencies [config]
  (let [input-stream (stream/create-stream)
        event-stream (stream/create-stream)
        effect-stream (stream/create-stream)
        emit-input (:emit input-stream)
        emit-event (:emit event-stream)
        emit-effect (:emit effect-stream)
        animation-agency (animation/create-animation-agency (when config (aget config "animation")))
        blink-agency (blink/create-blink-agency (when config (aget config "blink")))
        tts-agency (tts/create-tts-agency (when config (aget config "tts")))
        lipsync-agency (lipsync/create-lipsync-agency (when config (aget config "lipSync")))
        prosodic-agency (prosodic/create-prosodic-agency (when config (aget config "prosodic")))
        unsubscribers (atom [])
        disposed? (atom false)]
    (letfn [(track! [unsubscribe]
              (swap! unsubscribers conj unsubscribe))

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
                    "animation" (.dispatch ^js animation-agency (clj->js (:command payload)))
                    "tts" (.dispatch ^js tts-agency (clj->js (:command payload)))
                    "lipSync" (.dispatch ^js lipsync-agency (clj->js (:command payload)))
                    "prosodic" (.dispatch ^js prosodic-agency (clj->js (:command payload)))
                    (emit-event {:type "error"
                                 :agency (or (:agency payload) "unknown")
                                 :message "Unknown Polymer agency"})))))

            (snapshot! []
              (clj->js {:blink (js->clj (.snapshot ^js blink-agency) :keywordize-keys true)
                        :tts (js->clj (.snapshot ^js tts-agency) :keywordize-keys true)
                        :lipSync (js->clj (.snapshot ^js lipsync-agency) :keywordize-keys true)
                        :prosodic (js->clj (.snapshot ^js prosodic-agency) :keywordize-keys true)
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

            (route-tts-event! [event]
              (case (:type event)
                "lipSync.command"
                (.dispatch ^js lipsync-agency (clj->js (:command event)))

                "ttsSpeechStarted"
                (.dispatch ^js prosodic-agency
                           (clj->js {:type "speechStarted"
                                     :sourceAgency "tts"
                                     :name (:name event)
                                     :engine (:engine event)}))

                "ttsWordBoundary"
                (.dispatch ^js prosodic-agency
                           (clj->js {:type "wordBoundary"
                                     :sourceAgency "tts"
                                     :word (:word event)
                                     :wordIndex (:wordIndex event)
                                     :observedElapsedSec (:observedElapsedSec event)
                                     :hostElapsedSec (:hostElapsedSec event)}))

                "ttsSpeechStopped"
                (.dispatch ^js prosodic-agency
                           (clj->js {:type "speechStopped"
                                     :sourceAgency "tts"
                                     :reason (:reason event)}))

                "ttsSpeechEnded"
                (.dispatch ^js prosodic-agency
                           (clj->js {:type "speechStopped"
                                     :sourceAgency "tts"
                                     :reason "completed"}))

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
      #js {:dispatch dispatch!
           :input (stream/writable-port input-stream dispatch!)
           :events (stream/readable-port event-stream)
           :effects (stream/readable-port effect-stream)
           :snapshot snapshot!
           :agency (fn [name]
                     (case name
                       "animation" animation-agency
                       "blink" blink-agency
                       "tts" tts-agency
                       "lipSync" lipsync-agency
                       "prosodic" prosodic-agency
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
                        (.dispose ^js tts-agency)
                        (.dispose ^js lipsync-agency)
                        (.dispose ^js prosodic-agency)
                        (.dispose ^js animation-agency)
                        ((:dispose input-stream))
                        ((:dispose event-stream))
                        ((:dispose effect-stream))))})))
