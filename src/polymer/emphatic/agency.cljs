(ns polymer.emphatic.agency
  (:require [polymer.emphatic.domain :as domain]
            [polymer.emphatic.goap :as goap]
            [polymer.emphatic.scheduler :as scheduler]
            [polymer.emphatic.snippet :as snippet]
            [polymer.emphatic.state :as state]
            [polymer.stream :as stream]))

;; Emphatic Expression owns linguistic stress during speech. Prosodic owns
;; speaking cadence. Emphatic analyzes utterance text, then spikes brow/head
;; AUs on emphasis word boundaries. It emits animation requests only — Animation
;; owns the Embody effector.

(defn plan-error-message [plan]
  (let [step (first (:steps plan))]
    (case (:reason step)
      "unsupported-command" (str "Emphatic plan failed: unsupported command " (:commandType step))
      (str "Emphatic plan failed: " (:reason step)))))

(defn should-emit-plan? [plan]
  (or (false? (:ok plan))
      (:gesture plan)
      (seq (:gestures plan))
      (some #{"analyze-utterance" "remove-active-gestures"}
            (map :op (:steps plan)))))

(defn create-emphatic-agency [config]
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
                (emit-event {:type "emphaticPlanCreated"
                             :agency "emphatic"
                             :plan plan})))

            (schedule-gesture! [gesture-kind context]
              (when-let [snippet-data (snippet/build-gesture-snippet gesture-kind
                                                                      (:config @state-atom)
                                                                      context)]
                ((:schedule-gesture agency-scheduler) snippet-data {:autoPlay true})
                (let [scheduled-at (state/now-ms)]
                  (swap! state-atom state/record-schedule (:name snippet-data) gesture-kind scheduled-at)
                  (emit-event {:type "emphaticGestureScheduled"
                               :agency "emphatic"
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
                (emit-event {:type "emphaticStopped"
                             :agency "emphatic"
                             :reason reason
                             :stoppedAt stopped-at})))

            (execute-steps! [payload plan]
              (doseq [step (:steps plan)]
                (case (:op step)
                  "apply-config"
                  (do
                    (swap! state-atom state/configure (:config payload))
                    (emit-event {:type "emphaticConfigChanged"
                                 :agency "emphatic"
                                 :state @state-atom}))

                  "analyze-utterance"
                  (let [text (or (:text payload) (:utterance payload) "")
                        analyzed (domain/analyze-utterance text)
                        observed-at (state/now-ms)]
                    (swap! state-atom state/record-plan analyzed observed-at)
                    (emit-event {:type "emphaticPlanCreated"
                                 :agency "emphatic"
                                 :text text
                                 :emphasisWords (:emphasisWords analyzed)
                                 :observedAt observed-at}))

                  "record-speech-start"
                  (let [started-at (state/now-ms)]
                    (swap! state-atom state/record-speech-start started-at (:name payload) (:text payload))
                    (emit-event {:type "emphaticSpeechStarted"
                                 :agency "emphatic"
                                 :name (:name payload)
                                 :text (:text payload)
                                 :startedAt started-at}))

                  "record-speech-stop"
                  (let [stopped-at (state/now-ms)
                        reason (or (:reason payload) "requested")]
                    (swap! state-atom state/clear-active reason stopped-at)
                    (emit-event {:type "emphaticStopped"
                                 :agency "emphatic"
                                 :reason reason
                                 :stoppedAt stopped-at}))

                  "record-word-boundary"
                  (let [word (state/clean-word (:word payload))
                        word-index (int (state/number-or (:wordIndex payload)
                                                         (:wordIndex @state-atom)))
                        observed-at (state/now-ms)]
                    (swap! state-atom state/record-word-boundary word word-index observed-at)
                    (emit-event {:type "emphaticWordBoundary"
                                 :agency "emphatic"
                                 :word word
                                 :wordIndex word-index
                                 :observedAt observed-at}))

                  "build-gesture-snippet"
                  nil

                  "schedule-animation"
                  (when-let [gesture (or (:gesture step) (:gesture plan))]
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
                    (emit-event {:type "emphaticConfigChanged"
                                 :agency "emphatic"
                                 :state @state-atom}))

                  ("ignore" "fail")
                  nil

                  nil)))

            (dispatch! [command]
              (when-not @disposed?
                (let [payload (js->clj command :keywordize-keys true)
                      plan (goap/plan-command payload @state-atom)]
                  (emit-input {:type "command"
                               :agency "emphatic"
                               :command payload})
                  (emit-plan! plan)
                  (if (false? (:ok plan))
                    (emit-event {:type "error"
                                 :agency "emphatic"
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
           :analyzeUtterance (fn [text] (dispatch! #js {:type "analyzeUtterance" :text text}))
           :speechStarted (fn [name text] (dispatch! #js {:type "speechStarted" :name name :text text}))
           :wordBoundary (fn
                           ([word] (dispatch! #js {:type "wordBoundary" :word word}))
                           ([word word-index]
                            (dispatch! #js {:type "wordBoundary"
                                            :word word
                                            :wordIndex word-index})))
           :stop (fn [] (dispatch! #js {:type "stop"}))
           :reset (fn [] (dispatch! #js {:type "reset"}))
           :dispose (fn []
                      (when-not @disposed?
                        (reset! disposed? true)
                        ((:dispose agency-scheduler))
                        ((:dispose input-stream))
                        ((:dispose event-stream))
                        ((:dispose effect-stream))))})))
