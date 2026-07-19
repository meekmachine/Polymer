(ns polymer.camera-context.agency
  (:require [polymer.camera-context.domain :as domain]
            [polymer.camera-context.planner :as planner]
            [polymer.camera-context.scheduler :as scheduler]
            [polymer.camera-context.state :as state]
            [polymer.stream :as stream]))

;; Camera Context publishes stable camera-relative facts. It does not own gaze
;; behavior and does not import a rendering engine. Runtime-specific code can
;; observe the camera and send plain pose/target facts into this agency.

(defn now-ms
  []
  (.now js/Date))

(defn create-camera-context-agency
  [config]
  (let [input-stream (stream/create-stream)
        event-stream (stream/create-stream)
        effect-stream (stream/create-stream)
        state-atom (atom (state/config->state config))
        sequence (atom 0)
        disposed? (atom false)
        emit-input (:emit input-stream)
        emit-event (:emit event-stream)
        scheduler-atom (atom nil)]
    (letfn [(publish-fact! [fact]
              (let [published-at (now-ms)
                    payload (assoc fact
                                   :type "camera.fact"
                                   :publishedAt published-at)]
                (swap! state-atom state/record-published fact published-at)
                (emit-event payload)))

            (invalidate-stale! [reason]
              (when (state/can-invalidate? @state-atom)
                (let [invalidated-at (now-ms)]
                  (swap! state-atom state/record-stale invalidated-at reason)
                  (emit-event (assoc (:lastInvalidation @state-atom)
                                     :type "camera.stale")))))

            (emit-status! [status reason]
              (emit-event (cond-> {:type "camera.status"
                                   :agency "cameraContext"
                                   :status status
                                   :stale (:stale @state-atom)
                                   :at (now-ms)}
                            reason (assoc :reason reason))))

            (schedule-camera-update! [payload]
              (let [id (swap! sequence inc)
                    observed-at (now-ms)
                    fact (domain/normalize-camera-fact payload
                                                       (:config @state-atom)
                                                       observed-at
                                                       id)]
                (swap! state-atom state/record-camera-update fact)
                (when-let [agency-scheduler @scheduler-atom]
                  ((:schedule-camera-update agency-scheduler) fact))))

            (dispatch! [command]
              (when-not @disposed?
                (let [payload (domain/data-map command)
                      plan (planner/plan-command payload @state-atom (now-ms))
                      failure (first (filter #(= "fail" (:op %)) (:steps plan)))]
                  (emit-input {:type "command"
                               :agency "cameraContext"
                               :command payload})
                  (swap! state-atom state/record-plan plan)
                  (if failure
                    (emit-event {:type "error"
                                 :agency "cameraContext"
                                 :message (:reason failure)
                                 :commandType (:commandType failure)})
                    (case (:type payload)
                      "configure"
                      (do
                        (swap! state-atom state/configure (:config payload))
                        (emit-status! (:status @state-atom) "configured"))

                      "updateCamera"
                      (schedule-camera-update! payload)

                      "publishCameraFacts"
                      (when-let [agency-scheduler @scheduler-atom]
                        ((:publish-now agency-scheduler)))

                      "invalidateStale"
                      (when-let [agency-scheduler @scheduler-atom]
                        ((:invalidate-stale agency-scheduler)
                         (or (:reason payload) "manual")))

                      "reset"
                      (do
                        (when-let [agency-scheduler @scheduler-atom]
                          ((:reset agency-scheduler)))
                        (swap! state-atom state/reset-state)
                        (emit-status! "idle" "reset"))

                      nil)))))

            (dispatch-type! [type]
              (dispatch! #js {:type type}))]
      (let [agency-scheduler (scheduler/create-scheduler
                              {:state-atom state-atom
                               :publish-fact! publish-fact!
                               :invalidate-stale! invalidate-stale!})]
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
             :updateCamera (fn [facts]
                             (dispatch! #js {:type "updateCamera"
                                             :facts facts}))
             :invalidateStale (fn
                                ([] (dispatch-type! "invalidateStale"))
                                ([reason] (dispatch! #js {:type "invalidateStale"
                                                          :reason reason})))
             :reset (fn [] (dispatch-type! "reset"))
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
