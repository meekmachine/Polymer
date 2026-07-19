(ns polymer.blink.agency
  (:require [polymer.blink.planner :as planner]
            [polymer.blink.scheduler :as scheduler]
            [polymer.blink.state :as state]
            [polymer.stream :as stream]))

;; Blink is the smallest useful agency, so it is the proving ground for the
;; Polymer architecture:
;;
;; input   - commands coming from callers, workers, or later from other Polymer
;;           agencies.
;; events  - facts Blink observed or decided, such as a planned blink or a
;;           cross-agency signal.
;; effects - Blink leaves animation side effects to the Animation agency. The
;;           stream is intentionally empty today and exists for interface
;;           symmetry/future Blink-specific side effects.
;;
;; The agency owns only local Blink state and timers. It does not know about
;; UI components, legacy services, animation engines, DOM APIs, storage, audio,
;; video, or HTTP.

(defn js-command [type value]
  #js {:type type :value value})

(defn create-blink-agency [config]
  (let [input-stream (stream/create-stream)
        event-stream (stream/create-stream)
        effect-stream (stream/create-stream)
        state-atom (atom (state/config->state config))
        disposed? (atom false)
        emit-input (:emit input-stream)
        emit-event (:emit event-stream)
        scheduler-atom (atom nil)]
    (letfn [(record-plan! [plan]
              ;; Planning mutates only Blink's local counters/timestamps, then
              ;; leaves the runtime fact on the event stream. This is
              ;; intentional: scheduled blink counters are diagnostics and
              ;; agency-to-agency feedback, not render state. Publishing them
              ;; through a render-facing stream would make UI controls update
              ;; on every automatic blink.
              (let [now-ms (or (:created-at plan) (.now js/Date))]
                (swap! state-atom state/record-plan plan now-ms)))

            (refresh-auto! []
              (when-let [agency-scheduler @scheduler-atom]
                ((:refresh-auto agency-scheduler))))

            (stop-auto! []
              (when-let [agency-scheduler @scheduler-atom]
                ((:stop agency-scheduler))))

            (run-action! [action]
              (case (:op action)
                :trigger
                (when-let [agency-scheduler @scheduler-atom]
                  ((:trigger agency-scheduler)
                   (:reason action)
                   (:options action)
                   (:random-value action)
                   (:now-ms action)))

                :apply-state
                (do
                  (swap! state-atom state/apply-command (:payload action))
                  (emit-event {:type "blinkConfigChanged"
                               :agency "blink"
                               :state @state-atom}))

                :refresh-auto
                (refresh-auto!)

                :stop-auto
                (stop-auto!)

                :error
                (emit-event {:type "error"
                             :agency "blink"
                             :message (:message action)})

                (emit-event {:type "error"
                             :agency "blink"
                             :message (str "Unknown Blink planner action: " (:op action))})))

            (dispatch! [command]
              (when-not @disposed?
                (let [payload (js->clj command :keywordize-keys true)
                      now-ms (.now js/Date)
                      random-value (js/Math.random)]
                  ;; Input is observable so tests/workers can see the same
                  ;; commands that the agency processes. This stream is not used
                  ;; to cause side effects.
                  (emit-input {:type "command"
                               :agency "blink"
                               :command payload})
                  (doseq [action (planner/plan-command @state-atom payload random-value now-ms)]
                    (run-action! action)))))]
      (let [agency-scheduler (scheduler/create-scheduler
                              {:state-atom state-atom
                               :emit-event emit-event
                               :record-plan! record-plan!})]
        (reset! scheduler-atom agency-scheduler)
        #js {:dispatch dispatch!
             :snapshot (fn [] (state/visible-state @state-atom))
             :input (stream/writable-port input-stream dispatch!)
             :events (stream/readable-port event-stream)
             :effects (stream/readable-port effect-stream)
             :subscribeInput (fn [listener] ((:subscribe input-stream) listener))
             :subscribeEvents (fn [listener] ((:subscribe event-stream) listener))
             :subscribeEffects (fn [listener] ((:subscribe effect-stream) listener))
             ;; Compatibility aliases while callers and tests migrate to the
             ;; explicit event API. "status" is now just the event stream;
             ;; "commands" points at the empty compatibility effect stream.
             :subscribe (fn [listener] ((:subscribe event-stream) listener))
             :subscribeStatus (fn [listener] ((:subscribe event-stream) listener))
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
             :schedulerQueue (fn []
                               (when-let [agency-scheduler @scheduler-atom]
                                 (clj->js ((:queue agency-scheduler)))))
             :dispose (fn []
                        (when-not @disposed?
                          (reset! disposed? true)
                          ((:dispose agency-scheduler))
                          ((:dispose input-stream))
                          ((:dispose event-stream))
                          ((:dispose effect-stream))))}))))
