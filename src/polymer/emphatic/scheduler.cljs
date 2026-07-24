(ns polymer.emphatic.scheduler)

(defn now-ms []
  (.now js/Date))

(defn effector-target [channel]
  (let [target (:target channel)]
    (case (:type target)
      "au" (case (:id target)
             53 "head"
             54 "head"
             55 "head"
             "face")
      (:type target))))

(def snippet-effectors-xf
  (comp
   (map effector-target)
   (remove nil?)
   (distinct)))

(defn snippet-effectors [snippet]
  (into [] snippet-effectors-xf (:channels snippet)))

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
                                     :agency "emphatic"
                                     :requestId (:name snippet-data)
                                     :snippetName (:name snippet-data)
                                     :effectors effectors})]
                (emit-event {:type "animation.requestScheduleSnippet"
                             :agency "emphatic"
                             :requestId (:requestId entry)
                             :snippet snippet-data
                             :options options
                             :effectors effectors
                             :queueIndex (:queueIndex entry)
                             :queuedAt (:queuedAt entry)})
                entry))

            (remove-animation! [name reason]
              (let [entry (enqueue! {:type "removeAnimation"
                                     :agency "emphatic"
                                     :requestId name
                                     :name name
                                     :reason reason})]
                (emit-event {:type "animation.requestRemoveSnippet"
                             :agency "emphatic"
                             :requestId name
                             :name name
                             :reason reason
                             :queueIndex (:queueIndex entry)
                             :queuedAt (:queuedAt entry)})
                entry))]
      {:schedule-gesture
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
