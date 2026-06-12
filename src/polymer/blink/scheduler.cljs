(ns polymer.blink.scheduler
  (:require [polymer.blink.planner :as planner]
            [polymer.blink.snippet :as snippet]
            [polymer.blink.state :as state]))

(defn now-ms [] (.now js/Date))

(defn clear-timeout! [timer-atom]
  (when-let [timer @timer-atom]
    (js/clearTimeout timer)
    (reset! timer-atom nil)))

(defn create-scheduler [{:keys [state-atom emit-status emit-command]}]
  (let [timer-atom (atom nil)
        disposed? (atom false)
        scheduling? (atom false)]
    (letfn [(record-plan! [plan timestamp]
              (swap! state-atom
                     (fn [current]
                       (state/normalize-state
                        (cond-> (assoc current
                                       :lastBlinkTime timestamp
                                       :scheduledBlinkCount (+ (:scheduledBlinkCount current) (:count plan)))
                          (= (:kind plan) :burst)
                          (update :scheduledBurstCount inc))))))

            (emit-plan! [plan source]
              (when-not @scheduling?
                (reset! scheduling? true)
                (try
                  (let [timestamp (now-ms)
                        blink-state @state-atom
                        planned-snippet (snippet/build-blink-snippet blink-state plan timestamp)]
                    (record-plan! plan timestamp)
                    (emit-status {:type "blinkPlanned"
                                  :agency "blink"
                                  :source source
                                  :plan (assoc plan :totalDuration (planner/plan-total-duration plan))
                                  :state (state/visible-state @state-atom)})
                    (when (>= (:frequency @state-atom) 40)
                      (emit-status {:type "signal"
                                    :agency "blink"
                                    :signal "blink-fast"
                                    :state (state/visible-state @state-atom)}))
                    (emit-command {:type "scheduleSnippet"
                                   :agency "blink"
                                   :snippet planned-snippet
                                   :options {:autoPlay true}})
                    (js/setTimeout
                     #(emit-command {:type "removeSnippet"
                                     :agency "blink"
                                     :name (:name planned-snippet)})
                     (+ 50 (* 1000 (:maxTime planned-snippet)))))
                  (finally
                    (reset! scheduling? false)))))

            (schedule-next! []
              (clear-timeout! timer-atom)
              (when (and (not @disposed?) (:enabled @state-atom) (pos? (:frequency @state-atom)))
                (let [plan (planner/auto-plan @state-atom)
                      delay (planner/next-delay-ms @state-atom plan)]
                  (reset! timer-atom
                          (js/setTimeout
                           (fn []
                             (emit-plan! plan "auto")
                             (schedule-next!))
                           delay)))))]
      {:start schedule-next!

       :stop
       (fn []
         (clear-timeout! timer-atom))

       :trigger
       (fn [options]
         (emit-plan! (planner/manual-plan @state-atom options) "manual"))

       :reschedule
       schedule-next!

       :dispose
       (fn []
         (reset! disposed? true)
         (clear-timeout! timer-atom))})))
