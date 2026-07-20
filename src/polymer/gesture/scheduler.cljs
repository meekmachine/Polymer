(ns polymer.gesture.scheduler)

;; Gesture has no direct runtime effector. Its scheduler owns ordering,
;; replacement, cancellation, and diagnostics, then emits animation requests for
;; the Animation agency to accept or reject through its own scheduler/runtime path.

(defn now-ms []
  (.now js/Date))

(defn channel-effector [channel]
  (get-in channel [:target :id]))

(defn snippet-effectors [snippet]
  (->> (:channels snippet)
       (keep channel-effector)
       distinct
       vec))

(defn queue-entry [effect queue-index]
  (assoc effect
         :queueIndex queue-index
         :queuedAt (now-ms)))

(defn create-scheduler [{:keys [emit-event]}]
  (let [queue (atom [])
        disposed? (atom false)]
    (letfn [(enqueue! [effect]
              (let [entry (queue-entry effect (count @queue))]
                (swap! queue conj entry)
                entry))

            (schedule-animation! [snippet-data options]
              (let [effectors (snippet-effectors snippet-data)
                    entry (enqueue! {:type "scheduleAnimation"
                                     :agency "gesture"
                                     :requestId (:name snippet-data)
                                     :snippetName (:name snippet-data)
                                     :effectors effectors})]
                (emit-event {:type "animation.requestScheduleSnippet"
                             :agency "gesture"
                             :requestId (:requestId entry)
                             :snippet snippet-data
                             :options options
                             :effectors effectors
                             :queueIndex (:queueIndex entry)
                             :queuedAt (:queuedAt entry)})
                entry))

            (remove-animation! [name reason]
              (let [entry (enqueue! {:type "removeAnimation"
                                     :agency "gesture"
                                     :requestId name
                                     :name name
                                     :reason reason})]
                (emit-event {:type "animation.requestRemoveSnippet"
                             :agency "gesture"
                             :requestId name
                             :name name
                             :reason reason
                             :queueIndex (:queueIndex entry)
                             :queuedAt (:queuedAt entry)})
                entry))]
      {:schedule
       (fn [snippet-data options]
         (when-not @disposed?
           (schedule-animation! snippet-data options)))

       :remove
       (fn [name reason]
         (when-not @disposed?
           (remove-animation! name reason)))

       :remove-many
       (fn [names reason]
         (when-not @disposed?
           (doseq [name names]
             (remove-animation! name reason))))

       :queue
       (fn []
         @queue)

       :dispose
       (fn []
         (reset! disposed? true)
         (reset! queue []))})))
