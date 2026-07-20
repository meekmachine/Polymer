(ns polymer.gesture.agency
  (:require [polymer.gesture.goap :as goap]
            [polymer.gesture.scheduler :as scheduler]
            [polymer.gesture.snippet :as snippet]
            [polymer.gesture.state :as state]
            [polymer.stream :as stream]))

;; Gesture consumes authored arm/hand gesture snapshots and emits animation
;; intent. It does not own LoomLarge persistence, React UI, Three.js bones, or the
;; Embody animation runtime.

(defn js-command [type value]
  #js {:type type :value value})

(defn plan-error-message [plan]
  (let [step (or (some #(when (= "fail" (:op %)) %) (:steps plan))
                 (first (:steps plan)))]
    (case (:reason step)
      "unsupported-command" (str "Gesture plan failed: unsupported command " (:commandType step))
      "missing-emoji" "Gesture playEmoji command requires emoji"
      "missing-gesture" "Gesture command requires gestureId"
      "missing-goal" "Gesture goal command requires a gesture goal, intent, tag, scope, emoji, or effector constraint"
      "unknown-gesture" (str "Unknown gesture: " (:gestureId step))
      "gesture-has-no-motion" (str "Gesture has no bone motion: " (:gestureId step))
      "empty-library" "Gesture plan failed: gesture library is empty"
      "no-gesture-candidate" "Gesture plan failed: no gesture matched the goal"
      (str "Gesture plan failed: " (:reason step)))))

(defn should-emit-plan? [plan]
  (or (false? (:ok plan))
      (not (:noop plan))
      (contains? goap/playable-command-types (get-in plan [:goal :type]))))

(defn public-plan [plan]
  (-> plan
      (dissoc :gesture)
      (update :goal dissoc :gesture :activeSnippets :config)))

(defn create-gesture-agency [config]
  (let [input-stream (stream/create-stream)
        event-stream (stream/create-stream)
        effect-stream (stream/create-stream)
        state-atom (atom (state/config->state config))
        disposed? (atom false)
        completion-timers (atom {})
        emit-input (:emit input-stream)
        emit-event (:emit event-stream)
        agency-scheduler (scheduler/create-scheduler {:emit-event emit-event})]
    (letfn [(clear-completion! [name]
              (when-let [timer (get @completion-timers name)]
                (js/clearTimeout timer)
                (swap! completion-timers dissoc name)))

            (clear-all-completions! []
              (doseq [[_ timer] @completion-timers]
                (js/clearTimeout timer))
              (reset! completion-timers {}))

            (complete-active! [name]
              (when (get-in @state-atom [:activeSnippets name])
                (remove-active! [name] "completed")))

            (schedule-completion! [snippet-data]
              (let [name (:name snippet-data)
                    max-time (:maxTime snippet-data)]
                (clear-completion! name)
                (when (and name
                           (not (:loop snippet-data))
                           (state/finite-number? max-time)
                           (pos? max-time))
                  (let [timer (js/setTimeout #(complete-active! name)
                                             (+ 50 (* 1000 max-time)))]
                    (swap! completion-timers assoc name timer)))))

            (emit-plan! [plan]
              (when (should-emit-plan? plan)
                (emit-event {:type "gesturePlanCreated"
                             :agency "gesture"
                             :plan (public-plan plan)})))

            (remove-active! [names reason]
              (let [removed-at (state/now-ms)]
                (doseq [name names]
                  (clear-completion! name))
                ((:remove-many agency-scheduler) names reason)
                (doseq [name names]
                  (swap! state-atom state/record-remove name reason removed-at)
                  (emit-event {:type "gestureRemoved"
                               :agency "gesture"
                               :name name
                               :reason reason
                               :removedAt removed-at}))))

            (schedule-gesture! [gesture context]
              (let [context (or context {})
                    snippet-data (snippet/build-gesture-snippet gesture
                                                                (:config @state-atom)
                                                                context)]
                (if snippet-data
                  (do
                    ((:schedule agency-scheduler) snippet-data {:autoPlay true})
                    (let [scheduled-at (state/now-ms)]
                      (swap! state-atom state/record-schedule gesture snippet-data scheduled-at)
                      (emit-event {:type "gestureScheduled"
                                   :agency "gesture"
                                   :gestureId (:id gesture)
                                   :gestureName (:name gesture)
                                   :emoji (:emoji gesture)
                                   :scope (:scope gesture)
                                   :affectedBones (:affectedBones gesture)
                                   :name (:name snippet-data)
                                   :trigger (:trigger context)
                                   :intent (:intent context)
                                   :scheduledAt scheduled-at}))
                    (schedule-completion! snippet-data)
                    snippet-data)
                  (emit-event {:type "error"
                               :agency "gesture"
                               :message (str "Gesture produced no animation channels: " (:id gesture))}))))

            (dispatch! [command]
              (when-not @disposed?
                (let [payload (state/js-data command)
                      type (:type payload)
                      now (state/now-ms)
                      plan (goap/plan-command payload @state-atom now)]
                  (emit-input {:type "command"
                               :agency "gesture"
                               :command payload})
                  (emit-plan! plan)
                  (if (false? (:ok plan))
                    (emit-event {:type "error"
                                 :agency "gesture"
                                 :message (plan-error-message plan)})
                    (case type
                      "configure"
                      (do
                        (swap! state-atom state/configure (:config payload))
                        (emit-event {:type "gestureConfigChanged"
                                     :agency "gesture"
                                     :state (state/visible-state-map @state-atom)}))

                      "loadGestures"
                      (let [loaded-at (state/now-ms)]
                        (swap! state-atom state/load-gestures
                               (or (:gestures payload)
                                   (:characterGestures payload)
                                   (:gestureLibrary payload))
                               (or (:emojiMappings payload) (:gestureEmojiMappings payload))
                               loaded-at)
                        (emit-event {:type "gestureLibraryUpdated"
                                     :agency "gesture"
                                     :gestureCount (count (:gestures @state-atom))
                                     :emojiCount (count (:emojiMappings @state-atom))
                                     :updatedAt loaded-at}))

                      ("playGesture" "playEmoji" "gesture.goal" "performGestureGoal" "playGoal")
                      (when-not (:noop plan)
                        (doseq [step (:steps plan)]
                          (when (= "remove-active-gestures" (:op step))
                            (remove-active! (:names step) (:reason step))))
                        (schedule-gesture! (:gesture plan) (merge payload (:context plan))))

                      "stopGesture"
                      (remove-active! (:removeNames plan) (:removeReason plan))

                      "stopAll"
                      (remove-active! (:removeNames plan) (:removeReason plan))

                      "reset"
                      (do
                        (remove-active! (:removeNames plan) (:removeReason plan))
                        (reset! state-atom (state/config->state nil))
                        (emit-event {:type "gestureConfigChanged"
                                     :agency "gesture"
                                     :state (state/visible-state-map @state-atom)}))

                      (emit-event {:type "error"
                                   :agency "gesture"
                                   :message (str "Unknown Gesture command: " type)}))))))]
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
           :loadGestures (fn
                           ([gestures] (dispatch! #js {:type "loadGestures" :gestures gestures}))
                           ([gestures emoji-mappings]
                            (dispatch! #js {:type "loadGestures"
                                            :gestures gestures
                                            :emojiMappings emoji-mappings})))
           :playGesture (fn [gesture-id] (dispatch! #js {:type "playGesture" :gestureId gesture-id}))
           :playEmoji (fn [emoji] (dispatch! #js {:type "playEmoji" :emoji emoji}))
           :performGoal (fn [goal] (dispatch! #js {:type "gesture.goal" :goal goal}))
           :stopGesture (fn [gesture-id] (dispatch! #js {:type "stopGesture" :gestureId gesture-id}))
           :stopAll (fn [] (dispatch! #js {:type "stopAll"}))
           :reset (fn [] (dispatch! #js {:type "reset"}))
           :dispose (fn []
                      (when-not @disposed?
                        (reset! disposed? true)
                        (clear-all-completions!)
                        ((:dispose agency-scheduler))
                        ((:dispose input-stream))
                        ((:dispose event-stream))
                        ((:dispose effect-stream))))})))
