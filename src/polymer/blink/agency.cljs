(ns polymer.blink.agency
  (:require [polymer.blink.scheduler :as scheduler]
            [polymer.blink.state :as state]
            [polymer.stream :as stream]))

;; Blink is the smallest useful agency, so it is the proving ground for the
;; Polymer architecture:
;;
;; input   - commands coming from LoomLarge UI, backend messages, workers, or
;;           later from other Polymer agencies.
;; state   - renderable snapshots. React can read this, but React does not own
;;           the state.
;; events  - facts Blink observed or decided, such as a planned blink or a
;;           cross-agency signal.
;; effects - requested side effects. The host interpreter decides how to turn
;;           these into engine calls.
;;
;; The agency owns only local Blink state and timers. It does not know about
;; React components, Latticework, Loom3/Embody, DOM APIs, storage, audio, video,
;; or HTTP.

(defn js-command [type value]
  #js {:type type :value value})

(defn create-blink-agency [config]
  (let [input-stream (stream/create-stream)
        state-stream (stream/create-stream)
        event-stream (stream/create-stream)
        effect-stream (stream/create-stream)
        state-atom (atom (state/config->state config))
        disposed? (atom false)
        emit-input (:emit input-stream)
        emit-state (:emit state-stream)
        emit-event (:emit event-stream)
        emit-effect (:emit effect-stream)
        scheduler-atom (atom nil)]
    (letfn [(publish-state! []
              ;; State events are the single renderable Blink snapshot source.
              ;; UI code should subscribe here instead of keeping its own copy
              ;; of Blink service state.
              (emit-state {:type "state"
                           :agency "blink"
                           :state @state-atom}))

            (record-plan! [plan]
              ;; Planning mutates only Blink's local counters/timestamps, then
              ;; publishes a new state snapshot. Animation scheduling remains an
              ;; output effect, not local state mutation.
              (let [now-ms (or (:created-at plan) (.now js/Date))]
                (swap! state-atom state/record-plan plan now-ms)
                (publish-state!)))

            (refresh-auto! []
              (when-let [agency-scheduler @scheduler-atom]
                ((:refresh-auto agency-scheduler))))

            (dispatch! [command]
              (when-not @disposed?
                (let [payload (js->clj command :keywordize-keys true)
                      type (:type payload)]
                  ;; Input is observable so tests/workers can see the same
                  ;; commands that the agency processes. This stream is not used
                  ;; to cause side effects.
                  (emit-input {:type "command"
                               :agency "blink"
                               :command payload})
                  (case type
                    "triggerBlink"
                    (when-let [agency-scheduler @scheduler-atom]
                      ((:trigger agency-scheduler) (:options payload)))

                    ("enable" "disable" "setFrequency" "setDuration" "setIntensity"
                     "setRandomness" "setLeftEyeIntensity" "setRightEyeIntensity"
                     "setBurstEnabled" "setBurstFrequency" "setBurstCount" "setBurstGap"
                     "configure" "reset")
                    (do
                      (swap! state-atom state/apply-command payload)
                      (publish-state!)
                      (refresh-auto!))

                    (emit-event {:type "error"
                                 :agency "blink"
                                 :message (str "Unknown Blink command: " type)})))))]
      (let [agency-scheduler (scheduler/create-scheduler
                              {:state-atom state-atom
                               :emit-event emit-event
                               :emit-effect emit-effect
                               :record-plan! record-plan!})]
        (reset! scheduler-atom agency-scheduler)
        (publish-state!)
        #js {:dispatch dispatch!
             :snapshot (fn [] (state/visible-state @state-atom))
             :input (stream/writable-port input-stream dispatch!)
             :state (stream/readable-port state-stream)
             :events (stream/readable-port event-stream)
             :effects (stream/readable-port effect-stream)
             :subscribeInput (fn [listener] ((:subscribe input-stream) listener))
             :subscribeState (fn [listener] ((:subscribe state-stream) listener))
             :subscribeEvents (fn [listener] ((:subscribe event-stream) listener))
             :subscribeEffects (fn [listener] ((:subscribe effect-stream) listener))
             ;; Compatibility aliases while LoomLarge and tests are still being
             ;; migrated. "status" means state + events; "commands" now means
             ;; host effects.
             :subscribe (fn [listener] (stream/subscribe-many [state-stream event-stream] listener))
             :subscribeStatus (fn [listener] (stream/subscribe-many [state-stream event-stream] listener))
             :subscribeCommands (fn [listener] ((:subscribe effect-stream) listener))
             :enable (fn [] (dispatch! (js-command "enable" true)))
             :disable (fn [] (dispatch! (js-command "disable" false)))
             :triggerBlink (fn
                             ([] (dispatch! #js {:type "triggerBlink"}))
                             ([options] (dispatch! #js {:type "triggerBlink" :options options})))
             :setFrequency (fn [value] (dispatch! (js-command "setFrequency" value)))
             :setDuration (fn [value] (dispatch! (js-command "setDuration" value)))
             :setIntensity (fn [value] (dispatch! (js-command "setIntensity" value)))
             :setRandomness (fn [value] (dispatch! (js-command "setRandomness" value)))
             :setLeftEyeIntensity (fn [value] (dispatch! (js-command "setLeftEyeIntensity" value)))
             :setRightEyeIntensity (fn [value] (dispatch! (js-command "setRightEyeIntensity" value)))
             :setBurstEnabled (fn [value] (dispatch! (js-command "setBurstEnabled" value)))
             :setBurstFrequency (fn [value] (dispatch! (js-command "setBurstFrequency" value)))
             :setBurstCount (fn [value] (dispatch! (js-command "setBurstCount" value)))
             :setBurstGap (fn [value] (dispatch! (js-command "setBurstGap" value)))
             :reset (fn [] (dispatch! #js {:type "reset"}))
             :dispose (fn []
                        (when-not @disposed?
                          (reset! disposed? true)
                          ((:dispose agency-scheduler))
                          ((:dispose input-stream))
                          ((:dispose state-stream))
                          ((:dispose event-stream))
                          ((:dispose effect-stream))))}))))
