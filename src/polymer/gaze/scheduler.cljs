(ns polymer.gaze.scheduler
  (:require [polymer.gaze.state :as state]))

;; The Gaze scheduler owns ordering and coalescing for look requests. A burst of
;; attention/camera updates should become one downstream request, not many
;; overlapping eye/head movements.

(defn now-ms
  []
  (.now js/Date))

(defn clear-timeout!
  [timeout-atom]
  (when-let [timeout @timeout-atom]
    (js/clearTimeout timeout)
    (reset! timeout-atom nil)))

(defn queue-entry
  [step index]
  (assoc step
         :agency "gaze"
         :queueIndex index
         :queuedAt (now-ms)))

(defn create-scheduler
  [{:keys [state-atom emit-event]}]
  (let [coalesce-timeout (atom nil)
        pending-request (atom nil)
        queue (atom [])
        disposed? (atom false)]
    (letfn [(enqueue! [step]
              (let [entry (queue-entry step (count @queue))]
                (swap! queue conj entry)
                entry))

            (publish-status! [status reason]
              (emit-event (cond-> {:type "gaze.status"
                                   :agency "gaze"
                                   :status status
                                   :mode (:mode @state-atom)
                                   :active (:active @state-atom)
                                   :enabled (get-in @state-atom [:config :enabled])
                                   :at (now-ms)}
                            reason (assoc :reason reason))))

            (publish-request-now! []
              (when-not @disposed?
                (clear-timeout! coalesce-timeout)
                (when-let [request @pending-request]
                  (reset! pending-request nil)
                  (enqueue! {:op "publish-request"
                             :requestId (:requestId request)})
                  (let [published-at (now-ms)]
                    (swap! state-atom state/record-requested request published-at)
                    (emit-event (assoc request :publishedAt published-at))))))

            (queue-gaze-request! [request]
              (when-not @disposed?
                (let [queued-at (now-ms)
                      request (assoc request :queuedAt queued-at)
                      delay-ms (get-in @state-atom [:config :coalesceMs])]
                  (reset! pending-request request)
                  (swap! state-atom state/record-pending request)
                  (clear-timeout! coalesce-timeout)
                  (enqueue! {:op "coalesce-request"
                             :requestId (:requestId request)
                             :coalesceMs delay-ms})
                  (if (pos? delay-ms)
                    (reset! coalesce-timeout
                            (js/setTimeout publish-request-now! delay-ms))
                    (publish-request-now!)))))

            (ignore-target! [plan reason]
              (let [ignored {:type "gaze.targetIgnored"
                             :agency "gaze"
                             :requestId (:requestId plan)
                             :rawTarget (:rawTarget plan)
                             :target (:target plan)
                             :reason reason
                             :ignoredAt (now-ms)}]
                (swap! state-atom state/record-ignored ignored)
                (emit-event ignored)))

            (cancel-pending! []
              (clear-timeout! coalesce-timeout)
              (reset! pending-request nil)
              (swap! state-atom assoc :pendingRequest nil))

            (execute-step! [step]
              (enqueue! step)
              (case (:op step)
                "apply-config"
                (do
                  (swap! state-atom state/configure (:config step))
                  (publish-status! (:status @state-atom) "configured"))

                "set-mode"
                (do
                  (swap! state-atom state/set-mode (:mode step) (now-ms))
                  (publish-status! (:status @state-atom) "mode"))

                "set-active"
                (do
                  (swap! state-atom state/set-active (:active step) (now-ms))
                  (publish-status! (:status @state-atom) "active"))

                "set-enabled"
                (do
                  (swap! state-atom state/set-enabled (:enabled step) (now-ms))
                  (publish-status! (if (:enabled step) "enabled" "disabled")
                                   (if (:enabled step) "enabled" "disabled")))

                "record-target"
                (swap! state-atom state/record-plan (:plan step))

                "publish-target-received"
                (emit-event {:type "gaze.targetReceived"
                             :agency "gaze"
                             :requestId (get-in step [:plan :requestId])
                             :rawTarget (get-in step [:plan :rawTarget])
                             :source (get-in step [:plan :source])
                             :label (get-in step [:plan :label])
                             :at (now-ms)})

                "publish-target-planned"
                (emit-event {:type "gaze.targetPlanned"
                             :agency "gaze"
                             :requestId (get-in step [:plan :requestId])
                             :rawTarget (get-in step [:plan :rawTarget])
                             :target (get-in step [:plan :target])
                             :delta (get-in step [:plan :delta])
                             :eyeDurationMs (get-in step [:plan :eyeDurationMs])
                             :headDurationMs (get-in step [:plan :headDurationMs])
                             :at (now-ms)})

                "ignore-target"
                (ignore-target! (:plan step) (:reason step))

                "queue-request"
                (queue-gaze-request! (:request step))

                "request-reset"
                (do
                  (cancel-pending!)
                  (swap! state-atom state/record-reset (:request step))
                  (emit-event (:request step)))

                "request-cancel"
                (do
                  (cancel-pending!)
                  (swap! state-atom state/record-cancel (:request step))
                  (emit-event (:request step)))

                "fail"
                (emit-event {:type "error"
                             :agency "gaze"
                             :message (:reason step)
                             :commandType (:commandType step)})

                nil))]
      {:schedule
       (fn [plan]
         (when-not @disposed?
           (doseq [step (:steps plan)]
             (execute-step! step))))

       :flush
       (fn []
         (publish-request-now!))

       :reset
       (fn []
         (cancel-pending!)
         (reset! queue []))

       :queue
       (fn []
         @queue)

       :dispose
       (fn []
         (reset! disposed? true)
         (cancel-pending!)
         (reset! queue []))})))
