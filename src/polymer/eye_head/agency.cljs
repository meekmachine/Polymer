(ns polymer.eye-head.agency
  (:require [polymer.eye-head.domain :as domain]
            [polymer.eye-head.goap :as goap]
            [polymer.eye-head.scheduler :as scheduler]
            [polymer.eye-head.state :as state]
            [polymer.stream :as stream]))

;; Eye/Head owns synchronized gaze movement. Peer agencies publish look
;; requests; this agency plans and schedules eye/head AU snippets for Animation.

(defn now-ms
  []
  (.now js/Date))

(defn create-eye-head-tracking-agency
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
                               :agency "eyeHeadTracking"
                               :command payload})
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
             :setTarget (fn [target]
                          (dispatch! #js {:type "setTarget"
                                          :target target}))
             :configure (fn [config]
                          (dispatch! #js {:type "configure"
                                          :config config}))
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
