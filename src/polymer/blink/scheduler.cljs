(ns polymer.blink.scheduler
  (:require [polymer.blink.planner :as planner]
            [polymer.blink.snippet :as snippet]))

(defn clear-timeout! [timer-atom]
  (when-let [timer @timer-atom]
    (js/clearTimeout timer)
    (reset! timer-atom nil)))

(defn create-scheduler [{:keys [state-atom emit-status emit-command record-plan!]}]
  (let [auto-timer (atom nil)
        cleanup-timers (atom #{})
        disposed? (atom false)]
    (letfn [(emit-plan! [plan snippet-data next-delay-ms]
              (emit-status {:type "blinkPlanned"
                            :agency "blink"
                            :plan plan
                            :snippetName (:name snippet-data)
                            :nextDelayMs next-delay-ms})
              (when (:fast? plan)
                (emit-status {:type "signal"
                              :agency "blink"
                              :signal "blink-fast"
                              :plan plan})))

            (schedule-cleanup! [snippet-data]
              (let [delay-ms (+ (snippet/snippet-duration-ms snippet-data) 50)
                    timer-ref (atom nil)
                    timer (js/setTimeout
                           (fn []
                             (swap! cleanup-timers disj @timer-ref)
                             (when-not @disposed?
                               (emit-command {:type "removeSnippet"
                                              :agency "blink"
                                              :name (:name snippet-data)})))
                           delay-ms)]
                (reset! timer-ref timer)
                (swap! cleanup-timers conj timer)))

            (trigger! [reason options]
              (when-not @disposed?
                (let [plan (planner/make-plan @state-atom reason options)
                      snippet-data (snippet/build-blink-snippet plan)]
                  (record-plan! plan)
                  (emit-command {:type "scheduleSnippet"
                                 :agency "blink"
                                 :snippet snippet-data
                                 :options {:autoPlay true}})
                  (emit-plan! plan snippet-data nil)
                  (schedule-cleanup! snippet-data)
                  snippet-data)))

            (schedule-next-auto! [extra-delay-ms]
              (clear-timeout! auto-timer)
              (when-not @disposed?
                (let [blink-state @state-atom
                      interval (planner/next-interval-ms blink-state (js/Math.random))]
                  (when (and (:enabled blink-state) interval)
                    (let [delay-ms (+ interval (or extra-delay-ms 0))]
                      (reset! auto-timer
                              (js/setTimeout
                               (fn []
                                 (reset! auto-timer nil)
                                 (when-let [snippet-data (trigger! "auto" nil)]
                                   (let [extra-ms (max 0 (- (snippet/snippet-duration-ms snippet-data)
                                                           (* 1000 (:duration @state-atom))))]
                                     (schedule-next-auto! extra-ms))))
                               delay-ms)))))))]
      {:trigger (fn [options] (trigger! "manual" options))
       :refresh-auto (fn [] (schedule-next-auto! 0))
       :stop (fn [] (clear-timeout! auto-timer))
       :dispose (fn []
                  (reset! disposed? true)
                  (clear-timeout! auto-timer)
                  (doseq [timer @cleanup-timers]
                    (js/clearTimeout timer))
                  (reset! cleanup-timers #{}))})))
