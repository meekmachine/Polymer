(ns polymer.hair.agency
  (:require [polymer.hair.domain :as domain]
            [polymer.hair.planner :as planner]
            [polymer.hair.scheduler :as scheduler]
            [polymer.hair.state :as state]
            [polymer.stream :as stream]))

;; Hair owns profile-like hair state and movement-derived hair requests. It does
;; not import a host app or rendering engine; a runtime-specific effector can
;; consume hair.requestRuntime messages and apply materials, morphs, or physics.

(defn now-ms
  []
  (.now js/Date))

(defn create-hair-agency
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
                      plan (planner/plan-command payload @state-atom (now-ms))]
                  (emit-input {:type "command"
                               :agency "hair"
                               :command payload})
                  (swap! state-atom state/record-plan plan)
                  (emit-event {:type "hairPlanCreated"
                               :agency "hair"
                               :plan plan})
                  (when-let [agency-scheduler @scheduler-atom]
                    ((:schedule agency-scheduler) payload plan)))))
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
             :reset (fn [] (dispatch-type! "reset"))
             :queue (fn [] (clj->js ((:queue agency-scheduler))))
             :dispose (fn []
                        (when-not @disposed?
                          (reset! disposed? true)
                          ((:dispose agency-scheduler))
                          ((:dispose input-stream))
                          ((:dispose event-stream))
                          ((:dispose effect-stream))))}))))
