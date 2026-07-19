(ns polymer.conversation.agency
  (:require [polymer.conversation.planner :as planner]
            [polymer.conversation.scheduler :as scheduler]
            [polymer.conversation.state :as state]
            [polymer.stream :as stream]))

;; Conversation is a peer agency in the Polymer character society. It accepts
;; transcript, response, and interruption facts as data; plans local turn-taking
;; work; schedules those actions; then emits requests/facts that TTS, Gaze,
;; Prosodic, and other agencies can decide how to handle.

(defn now-ms
  []
  (.now js/Date))

(defn js-command
  [type value]
  #js {:type type :value value})

(defn create-conversation-agency
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
                (let [payload (state/data-map command)
                      plan (planner/plan-command payload @state-atom (now-ms))]
                  ;; Input is observable for workers/tests and for future
                  ;; diagnostics. It is not host application state and should
                  ;; not be mirrored into a UI on every agency message.
                  (emit-input {:type "command"
                               :agency "conversation"
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
             :interrupt (fn
                          ([] (dispatch-type! "interrupt"))
                          ([reason] (dispatch! #js {:type "interrupt" :reason reason})))
             :setAutoRespond (fn [value]
                               (dispatch! #js {:type "configure"
                                               :config #js {:autoRespond value}}))
             :queue (fn [] (clj->js ((:queue agency-scheduler))))
             :dispose (fn []
                        (when-not @disposed?
                          (reset! disposed? true)
                          ((:dispose agency-scheduler))
                          ((:dispose input-stream))
                          ((:dispose event-stream))
                          ((:dispose effect-stream))))}))))
