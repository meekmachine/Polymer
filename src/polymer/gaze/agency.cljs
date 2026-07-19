(ns polymer.gaze.agency
  (:require [polymer.gaze.domain :as domain]
            [polymer.gaze.goap :as goap]
            [polymer.gaze.scheduler :as scheduler]
            [polymer.gaze.state :as state]
            [polymer.stream :as stream]))

;; Gaze converts attention and camera-relative facts into look-intent requests.
;; It does not move eyes or head directly. The downstream movement agency owns
;; the runtime side effects after accepting these request messages.

(defn now-ms
  []
  (.now js/Date))

(defn create-gaze-agency
  [config]
  (let [input-stream (stream/create-stream)
        event-stream (stream/create-stream)
        effect-stream (stream/create-stream)
        state-atom (atom (state/config->state config))
        disposed? (atom false)
        emit-input (:emit input-stream)
        emit-event (:emit event-stream)
        scheduler-atom (atom nil)]
    (letfn [(dispatch! [command]
              (when-not @disposed?
                (let [payload (domain/data-map command)
                      plan (goap/plan-command payload @state-atom (now-ms))]
                  (emit-input {:type "command"
                               :agency "gaze"
                               :command payload})
                  (swap! state-atom assoc :lastPlan plan)
                  (when-let [agency-scheduler @scheduler-atom]
                    ((:schedule agency-scheduler) plan)))))

            (dispatch-type! [type]
              (dispatch! #js {:type type}))]
      (let [agency-scheduler (scheduler/create-scheduler
                              {:state-atom state-atom
                               :emit-event emit-event})]
        (reset! scheduler-atom agency-scheduler)
        #js {:dispatch dispatch!
             :snapshot (fn [] (state/visible-state @state-atom))
             :input (stream/writable-port input-stream dispatch!)
             :events (stream/readable-port event-stream)
             :effects (stream/readable-port effect-stream)
             :subscribeInput (fn [listener] ((:subscribe input-stream) listener))
             :subscribeEvents (fn [listener] ((:subscribe event-stream) listener))
             :subscribeEffects (fn [listener] ((:subscribe effect-stream) listener))
             :subscribe (fn [listener] ((:subscribe event-stream) listener))
             :subscribeStatus (fn [listener] ((:subscribe event-stream) listener))
             :subscribeCommands (fn [listener] ((:subscribe effect-stream) listener))
             :setTarget (fn
                          ([target] (dispatch! #js {:type "setTarget"
                                                    :target target}))
                          ([target options] (dispatch! #js {:type "setTarget"
                                                            :target target
                                                            :options options})))
             :focusTarget (fn [target] (dispatch! #js {:type "focusTarget"
                                                       :target target}))
             :configure (fn [config] (dispatch! #js {:type "configure"
                                                     :config config}))
             :setMode (fn [mode] (dispatch! #js {:type "setMode"
                                                 :mode mode}))
             :setActive (fn [active] (dispatch! #js {:type "setActive"
                                                     :active active}))
             :enable (fn [] (dispatch-type! "enable"))
             :disable (fn [] (dispatch-type! "disable"))
             :reset (fn
                      ([] (dispatch-type! "reset"))
                      ([duration-ms] (dispatch! #js {:type "reset"
                                                     :durationMs duration-ms})))
             :cancel (fn
                       ([] (dispatch-type! "cancel"))
                       ([reason] (dispatch! #js {:type "cancel"
                                                 :reason reason})))
             :flush (fn []
                      (when-let [agency-scheduler @scheduler-atom]
                        ((:flush agency-scheduler))))
             :queue (fn []
                      (when-let [agency-scheduler @scheduler-atom]
                        (clj->js ((:queue agency-scheduler)))))
             :dispose (fn []
                        (when-not @disposed?
                          (reset! disposed? true)
                          ((:dispose agency-scheduler))
                          ((:dispose input-stream))
                          ((:dispose event-stream))
                          ((:dispose effect-stream))))}))))
