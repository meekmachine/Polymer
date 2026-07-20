(ns polymer.transcription.agency
  (:require [polymer.stream :as stream]
            [polymer.transcription.domain :as domain]
            [polymer.transcription.planner :as planner]
            [polymer.transcription.scheduler :as scheduler]
            [polymer.transcription.state :as state]))

;; Transcription turns provider recognition callbacks into stable speech facts.
;; The agency may later own a Web Speech or Azure STT effector, but peer agencies
;; should only see data: partial/final transcripts, status, diagnostics, and
;; provider lifecycle requests.

(defn now-ms
  []
  (.now js/Date))

(defn create-transcription-agency
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
                               :agency "transcription"
                               :command payload})
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
             :start (fn [] (dispatch-type! "start"))
             :stop (fn [] (dispatch-type! "stop"))
             :reset (fn [] (dispatch-type! "reset"))
             :queue (fn [] (clj->js ((:queue agency-scheduler))))
             :dispose (fn []
                        (when-not @disposed?
                          (reset! disposed? true)
                          ((:dispose agency-scheduler))
                          ((:dispose input-stream))
                          ((:dispose event-stream))
                          ((:dispose effect-stream))))}))))
