(ns polymer.eye-head.scheduler
  (:require [polymer.eye-head.state :as state]))

;; The Eye/Head scheduler is the boundary that replaces or cancels movement
;; snippets. It publishes animation requests as data; the Animation agency owns
;; the runtime side effect of playing those snippets.

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
         :agency "eyeHeadTracking"
         :queueIndex index
         :queuedAt (now-ms)))

(defn create-scheduler
  [{:keys [state-atom emit-event]}]
  (let [coalesce-timeout (atom nil)
        pending-work (atom nil)
        queue (atom [])
        disposed? (atom false)]
    (letfn [(enqueue! [step]
              (let [entry (queue-entry step (count @queue))]
                (swap! queue conj entry)
                entry))

            (emit-status! [reason]
              (emit-event {:type "eyeHeadTracking.status"
                           :agency "eyeHeadTracking"
                           :status (:status @state-atom)
                           :enabled (get-in @state-atom [:config :enabled])
                           :activeSnippetNames (:activeSnippetNames @state-atom)
                           :reason reason
                           :at (now-ms)}))

            (emit-remove-active! [reason]
              (doseq [name (:activeSnippetNames @state-atom)]
                (let [removed-at (now-ms)]
                  (swap! state-atom state/record-removed name reason removed-at)
                  (emit-event {:type "animation.requestRemoveSnippet"
                               :agency "eyeHeadTracking"
                               :requestId (str "eyeHeadTracking:remove:" removed-at)
                               :name name
                               :reason reason
                               :queuedAt removed-at}))))

            (publish-work-now! []
              (when-not @disposed?
                (clear-timeout! coalesce-timeout)
                (when-let [{:keys [kind request snippet]} @pending-work]
                  (reset! pending-work nil)
                  (when (get-in @state-atom [:config :replaceExisting])
                    (emit-remove-active! "replaced"))
                  (let [published-at (now-ms)]
                    (enqueue! {:op "publish-animation"
                               :requestId (:requestId request)
                               :name (:name snippet)})
                    (if (= kind "reset")
                      (swap! state-atom state/record-reset request snippet published-at)
                      (swap! state-atom state/record-scheduled request snippet published-at))
                    (emit-event {:type "animation.requestScheduleSnippet"
                                 :agency "eyeHeadTracking"
                                 :requestId (:requestId request)
                                 :snippet snippet
                                 :options {:autoPlay true
                                           :sourceAgency "eyeHeadTracking"}})))))

            (queue-work! [kind request snippet]
              (when-not @disposed?
                (let [queued-at (now-ms)
                      work {:kind kind
                            :request (assoc request :queuedAt queued-at)
                            :snippet snippet}
                      delay-ms (get-in @state-atom [:config :coalesceMs])]
                  (reset! pending-work work)
                  (swap! state-atom state/record-pending (:request work))
                  (clear-timeout! coalesce-timeout)
                  (enqueue! {:op "coalesce-work"
                             :kind kind
                             :requestId (:requestId request)
                             :coalesceMs delay-ms})
                  (if (pos? delay-ms)
                    (reset! coalesce-timeout
                            (js/setTimeout publish-work-now! delay-ms))
                    (publish-work-now!)))))

            (cancel-pending! []
              (clear-timeout! coalesce-timeout)
              (reset! pending-work nil)
              (swap! state-atom assoc :pendingRequest nil))

            (ignore-request! [request reason]
              (let [ignored {:type "eyeHeadTracking.requestIgnored"
                             :agency "eyeHeadTracking"
                             :requestId (:requestId request)
                             :reason reason
                             :target (:target request)
                             :ignoredAt (now-ms)}]
                (cancel-pending!)
                (swap! state-atom state/record-ignored ignored)
                (emit-event ignored)))

            (cancel-active! [request]
              (cancel-pending!)
              (emit-remove-active! (:reason request))
              (swap! state-atom state/record-cancel request)
              (emit-event {:type "eyeHeadTracking.cancelled"
                           :agency "eyeHeadTracking"
                           :requestId (:requestId request)
                           :reason (:reason request)
                           :at (now-ms)}))

            (execute-step! [step]
              (enqueue! step)
              (case (:op step)
                "apply-config"
                (do
                  (swap! state-atom state/configure (:config step))
                  (emit-status! (:reason step)))

                "set-enabled"
                (do
                  (swap! state-atom state/set-enabled (:enabled step))
                  (emit-status! (if (:enabled step) "enabled" "disabled")))

                "publish-status"
                (emit-status! (:reason step))

                "queue-gaze"
                (queue-work! "gaze" (:request step) (:snippet step))

                "queue-reset"
                (queue-work! "reset" (:request step) (:snippet step))

                "cancel-active"
                (cancel-active! (:request step))

                "ignore-request"
                (ignore-request! (:request step) (:reason step))

                "fail"
                (emit-event {:type "error"
                             :agency "eyeHeadTracking"
                             :message (:reason step)
                             :commandType (:commandType step)})

                nil))]
      {:schedule
       (fn [plan]
         (when-not @disposed?
           (swap! state-atom state/record-plan plan)
           (doseq [step (:steps plan)]
             (execute-step! step))))

       :flush
       (fn []
         (publish-work-now!))

       :queue
       (fn []
         @queue)

       :dispose
       (fn []
         (reset! disposed? true)
         (cancel-pending!)
         (reset! queue []))})))
