(ns polymer.hair.scheduler
  (:require [polymer.hair.domain :as domain]
            [polymer.hair.state :as state]))

;; Hair can receive high-frequency motion facts. The scheduler is therefore the
;; point that coalesces those facts into stable runtime requests, instead of
;; making UI or host code manage a per-frame stream.

(defn now-ms
  []
  (.now js/Date))

(defn clear-timeout!
  [timer-atom]
  (when-let [timer @timer-atom]
    (js/clearTimeout timer)
    (reset! timer-atom nil)))

(defn queue-entry
  [step index plan]
  (assoc step
         :agency "hair"
         :queueIndex index
         :commandType (:commandType plan)
         :queuedAt (now-ms)))

(defn create-scheduler
  [{:keys [state-atom emit-event]}]
  (let [queue (atom [])
        motion-timer (atom nil)
        pending-motion (atom nil)
        sequence (atom 0)
        disposed? (atom false)]
    (letfn [(enqueue! [step plan]
              (let [entry (queue-entry step (count @queue) plan)]
                (swap! queue conj entry)
                entry))

            (emit-runtime-request! [request]
              (swap! state-atom state/record-runtime-request request)
              (emit-event request))

            (publish-motion! []
              (when-not @disposed?
                (clear-timeout! motion-timer)
                (when-let [motion @pending-motion]
                  (reset! pending-motion nil)
                  (let [id (swap! sequence inc)
                        request (domain/motion-request motion (:physics @state-atom) id (now-ms))]
                    (emit-runtime-request! request)))))

            (schedule-motion! [motion]
              (when-not @disposed?
                (reset! pending-motion motion)
                (clear-timeout! motion-timer)
                (let [delay-ms (get-in @state-atom [:physics :coalesceMs])]
                  (if (pos? delay-ms)
                    (reset! motion-timer (js/setTimeout publish-motion! delay-ms))
                    (publish-motion!)))))

            (execute-step! [step command plan]
              (let [now (now-ms)]
                (enqueue! step plan)
                (case (:op step)
                  "apply-config"
                  (do
                    (swap! state-atom state/configure (or (:config step) (:config command)))
                    (emit-event {:type "hair.status"
                                 :agency "hair"
                                 :status "configured"
                                 :at now}))

                  "request-apply-state"
                  (emit-runtime-request! (domain/material-request @state-atom now))

                  "record-motion"
                  (swap! state-atom state/record-motion (:motion step))

                  "coalesce-motion"
                  (schedule-motion! (:lastMotion @state-atom))

                  "reset-state"
                  (do
                    (clear-timeout! motion-timer)
                    (reset! pending-motion nil)
                    (swap! state-atom state/reset-state)
                    (emit-event {:type "hair.status"
                                 :agency "hair"
                                 :status "idle"
                                 :reason "reset"
                                 :at now}))

                  "request-reset"
                  (emit-runtime-request! (domain/reset-request now))

                  "fail"
                  (emit-event {:type "error"
                               :agency "hair"
                               :message (:reason step)
                               :commandType (:commandType step)
                               :at now})

                  nil)))]
      {:schedule
       (fn [command plan]
         (when-not @disposed?
           (doseq [step (:steps plan)]
             (execute-step! step command plan))))

       :queue
       (fn []
         @queue)

       :dispose
       (fn []
         (reset! disposed? true)
         (clear-timeout! motion-timer)
         (reset! pending-motion nil)
         (reset! queue []))})))
