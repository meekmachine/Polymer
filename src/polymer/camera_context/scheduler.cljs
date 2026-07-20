(ns polymer.camera-context.scheduler)

;; The scheduler is the only Camera Context namespace that owns timers. Runtime
;; camera notifications can arrive at frame cadence, but the agency should only
;; publish stabilized facts after this coalescing boundary.

(defn now-ms
  []
  (.now js/Date))

(defn clear-timeout!
  [timer-atom]
  (when-let [timer @timer-atom]
    (js/clearTimeout timer)
    (reset! timer-atom nil)))

(defn queue-entry
  [effect index]
  (assoc effect
         :queueIndex index
         :queuedAt (now-ms)))

(defn create-scheduler
  [{:keys [state-atom publish-fact! invalidate-stale!]}]
  (let [publish-timer (atom nil)
        stale-timer (atom nil)
        pending-fact (atom nil)
        queue (atom [])
        disposed? (atom false)]
    (letfn [(enqueue! [effect]
              (let [entry (queue-entry effect (count @queue))]
                (swap! queue conj entry)
                entry))

            (schedule-stale! []
              (clear-timeout! stale-timer)
              (when-not @disposed?
                (let [delay-ms (get-in @state-atom [:config :staleAfterMs])
                      published-at (:lastPublishedAt @state-atom)]
                  (when (pos? delay-ms)
                    (reset! stale-timer
                            (js/setTimeout
                             (fn []
                               (reset! stale-timer nil)
                               (when (and (not @disposed?)
                                          (nil? @pending-fact)
                                          (= published-at (:lastPublishedAt @state-atom)))
                                 (enqueue! {:type "invalidateStale"
                                            :agency "cameraContext"
                                            :reason "timeout"})
                                 (invalidate-stale! "timeout")))
                             delay-ms))))))

            (publish-now! []
              (when-not @disposed?
                (clear-timeout! publish-timer)
                (when-let [fact @pending-fact]
                  (reset! pending-fact nil)
                  (enqueue! {:type "publishCameraFact"
                             :agency "cameraContext"
                             :sequence (:sequence fact)})
                  (publish-fact! fact)
                  (schedule-stale!))))

            (schedule-publish! [fact]
              (when-not @disposed?
                (reset! pending-fact fact)
                (clear-timeout! publish-timer)
                (clear-timeout! stale-timer)
                (let [delay-ms (get-in @state-atom [:config :coalesceMs])]
                  (enqueue! {:type "coalesceCameraFact"
                             :agency "cameraContext"
                             :sequence (:sequence fact)
                             :coalesceMs delay-ms})
                  (if (pos? delay-ms)
                    (reset! publish-timer
                            (js/setTimeout publish-now! delay-ms))
                    (publish-now!)))))]
      {:schedule-camera-update
       (fn [fact]
         (schedule-publish! fact))

       :publish-now
       (fn []
         (publish-now!))

       :invalidate-stale
       (fn [reason]
         (when-not @disposed?
           (clear-timeout! stale-timer)
           (enqueue! {:type "invalidateStale"
                      :agency "cameraContext"
                      :reason reason})
           (invalidate-stale! reason)))

       :reset
       (fn []
         (clear-timeout! publish-timer)
         (clear-timeout! stale-timer)
         (reset! pending-fact nil)
         (reset! queue []))

       :queue
       (fn []
         @queue)

       :dispose
       (fn []
         (reset! disposed? true)
         (clear-timeout! publish-timer)
         (clear-timeout! stale-timer)
         (reset! pending-fact nil)
         (reset! queue []))})))
