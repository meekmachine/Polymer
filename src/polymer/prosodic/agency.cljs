(ns polymer.prosodic.agency
  (:require [polymer.prosodic.goap :as goap]
            [polymer.prosodic.scheduler :as scheduler]
            [polymer.prosodic.snippet :as snippet]
            [polymer.prosodic.state :as state]
            [polymer.stream :as stream]))

;; Prosodic Expression consumes speech/blink/conversation facts and emits
;; animation intent. It does not own TTS, LipSync, host UI state, storage, or
;; the animation runtime. The character network routes peer facts here, then
;; routes Prosodic's animation requests to Polymer Animation.

(defn js-command [type value]
  #js {:type type :value value})

(defn plan-error-message [plan]
  (let [step (first (:steps plan))]
    (case (:reason step)
      "unsupported-command" (str "Prosodic plan failed: unsupported command " (:commandType step))
      (str "Prosodic plan failed: " (:reason step)))))

(defn should-emit-plan? [plan]
  ;; Ignore/no-op plans are routine for word boundaries. Emitting every one
  ;; would create noisy high-frequency diagnostics without changing behavior.
  (or (false? (:ok plan))
      (:gesture plan)
      (some #{"record-conversation-fact" "remove-active-gestures"}
            (map :op (:steps plan)))))

(defn create-prosodic-agency [config]
  (let [input-stream (stream/create-stream)
        event-stream (stream/create-stream)
        effect-stream (stream/create-stream)
        state-atom (atom (state/config->state config))
        disposed? (atom false)
        emit-input (:emit input-stream)
        emit-event (:emit event-stream)
        agency-scheduler (scheduler/create-scheduler {:emit-event emit-event})]
    (letfn [(emit-plan! [plan]
              (when (should-emit-plan? plan)
                (emit-event {:type "prosodicPlanCreated"
                             :agency "prosodic"
                             :plan plan})))

            (schedule-gesture! [gesture-kind context]
              (when-let [snippet-data (snippet/build-gesture-snippet gesture-kind
                                                                      (:config @state-atom)
                                                                      context)]
                ((:schedule-gesture agency-scheduler) snippet-data {:autoPlay true})
                (let [scheduled-at (state/now-ms)]
                  (swap! state-atom state/record-schedule (:name snippet-data) gesture-kind scheduled-at)
                  (emit-event {:type "prosodicGestureScheduled"
                               :agency "prosodic"
                               :gesture gesture-kind
                               :name (:name snippet-data)
                               :word (:word context)
                               :wordIndex (:wordIndex context)
                               :scheduledAt scheduled-at}))
                snippet-data))

            (remove-active! [reason]
              (let [active (:activeSnippets @state-atom)
                    removed-at (state/now-ms)]
                ((:remove-many agency-scheduler) active reason)
                (doseq [name active]
                  (swap! state-atom state/record-remove name reason removed-at))))

            (stop-local! [reason]
              (remove-active! reason)
              (let [stopped-at (state/now-ms)]
                (swap! state-atom state/clear-active reason stopped-at)
                (emit-event {:type "prosodicStopped"
                             :agency "prosodic"
                             :reason reason
                             :stoppedAt stopped-at})))

            (execute-steps! [payload plan]
              (doseq [step (:steps plan)]
                (case (:op step)
                  "apply-config"
                  (do
                    (swap! state-atom state/configure (:config payload))
                    (emit-event {:type "prosodicConfigChanged"
                                 :agency "prosodic"
                                 :state @state-atom}))

                  "clear-conversation-suppress"
                  (swap! state-atom state/clear-conversation-suppress)

                  "record-speech-start"
                  (let [started-at (state/now-ms)]
                    (swap! state-atom state/record-speech-start started-at (:name payload))
                    (emit-event {:type "prosodicSpeechStarted"
                                 :agency "prosodic"
                                 :name (:name payload)
                                 :startedAt started-at}))

                  "record-speech-stop"
                  (let [stopped-at (state/now-ms)
                        reason (or (:reason payload) "requested")]
                    (swap! state-atom state/clear-active reason stopped-at)
                    (emit-event {:type "prosodicStopped"
                                 :agency "prosodic"
                                 :reason reason
                                 :stoppedAt stopped-at}))

                  "record-word-boundary"
                  (let [word (state/clean-word (:word payload))
                        word-index (int (state/number-or (:wordIndex payload)
                                                         (:wordIndex @state-atom)))
                        observed-at (state/now-ms)]
                    (swap! state-atom state/record-word-boundary word word-index observed-at)
                    (emit-event {:type "prosodicWordBoundary"
                                 :agency "prosodic"
                                 :word word
                                 :wordIndex word-index
                                 :observedAt observed-at}))

                  "record-blink-fast-cue"
                  (swap! state-atom state/record-blink-fast-cue (state/now-ms))

                  "record-conversation-fact"
                  (let [observed-at (state/now-ms)]
                    (swap! state-atom state/record-conversation-fact payload observed-at)
                    (emit-event {:type "prosodicConversationFact"
                                 :agency "prosodic"
                                 :conversationType (:type payload)
                                 :text (:text payload)
                                 :turnId (:turnId payload)
                                 :observedAt observed-at}))

                  "build-gesture-snippet"
                  nil

                  "schedule-animation"
                  (when-let [gesture (:gesture plan)]
                    (schedule-gesture! gesture
                                       {:trigger (or (:type payload) "plan")
                                        :word (state/clean-word (:word payload))
                                        :wordIndex (int (state/number-or (:wordIndex payload)
                                                                         (:wordIndex @state-atom)))}))

                  "remove-active-gestures"
                  (remove-active! (or (:reason payload) "requested"))

                  "reset-state"
                  (do
                    (stop-local! "reset")
                    (reset! state-atom (state/config->state nil))
                    (emit-event {:type "prosodicConfigChanged"
                                 :agency "prosodic"
                                 :state @state-atom}))

                  ("ignore" "fail")
                  nil

                  nil)))

            (dispatch! [command]
              (when-not @disposed?
                (let [payload (js->clj command :keywordize-keys true)
                      plan (goap/plan-command payload @state-atom)]
                  (emit-input {:type "command"
                               :agency "prosodic"
                               :command payload})
                  (emit-plan! plan)
                  (if (false? (:ok plan))
                    (emit-event {:type "error"
                                 :agency "prosodic"
                                 :message (plan-error-message plan)})
                    (execute-steps! payload plan)))))]
      #js {:dispatch dispatch!
           :snapshot (fn [] (state/visible-state @state-atom))
           :schedulerQueue (fn [] (clj->js ((:queue agency-scheduler))))
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
           :speechStarted (fn [name] (dispatch! #js {:type "speechStarted" :name name}))
           :wordBoundary (fn
                           ([word] (dispatch! #js {:type "wordBoundary" :word word}))
                           ([word word-index]
                            (dispatch! #js {:type "wordBoundary"
                                            :word word
                                            :wordIndex word-index})))
           :blinkFast (fn [] (dispatch! #js {:type "blinkFast"}))
           :stop (fn [] (dispatch! #js {:type "stop"}))
           :reset (fn [] (dispatch! #js {:type "reset"}))
           :dispose (fn []
                      (when-not @disposed?
                        (reset! disposed? true)
                        ((:dispose agency-scheduler))
                        ((:dispose input-stream))
                        ((:dispose event-stream))
                        ((:dispose effect-stream))))})))
