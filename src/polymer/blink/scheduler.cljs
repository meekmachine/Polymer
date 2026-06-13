(ns polymer.blink.scheduler
  (:require [polymer.blink.planner :as planner]
            [polymer.blink.snippet :as snippet]))

;; The scheduler is the only Blink namespace that owns timers.
;;
;; It still does not call animation engines directly. When a timer fires it
;; creates plain effect data and pushes that data to the effect stream. The host
;; decides whether that effect becomes a Latticework animation call, a worker
;; message, a test assertion, or something else.

(defn clear-timeout! [timer-atom]
  (when-let [timer @timer-atom]
    (js/clearTimeout timer)
    (reset! timer-atom nil)))

(defn create-scheduler [{:keys [state-atom emit-event emit-effect record-plan!]}]
  (let [auto-timer (atom nil)
        cleanup-timers (atom #{})
        disposed? (atom false)]
    (letfn [(emit-plan! [plan snippet-data next-delay-ms]
              ;; Domain events describe what Blink decided. They are not
              ;; imperative requests. Other agencies can listen to them without
              ;; causing side effects.
              (emit-event {:type "blinkPlanned"
                           :agency "blink"
                           :plan plan
                           :snippetName (:name snippet-data)
                           :nextDelayMs next-delay-ms})
              (when (:fast? plan)
                ;; Cross-agency signals also live on the event stream. In the
                ;; current LoomLarge migration, the host interpreter turns this
                ;; specific signal into a small prosodic animation cue. Later a
                ;; Polymer prosodic agency can consume it directly.
                (emit-event {:type "signal"
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
                               ;; Effects are requested side effects. This one
                               ;; asks the host to remove the animation snippet
                               ;; that was scheduled for the blink.
                               (emit-effect {:type "animation.removeSnippet"
                                             :agency "blink"
                                             :effectId (:name snippet-data)
                                             :name (:name snippet-data)})))
                           delay-ms)]
                (reset! timer-ref timer)
                (swap! cleanup-timers conj timer)))

            (trigger! [reason options]
              (when-not @disposed?
                (let [plan (planner/make-plan @state-atom reason options)
                      snippet-data (snippet/build-blink-snippet plan)]
                  (record-plan! plan)
                  ;; The scheduler schedules nothing by itself. It emits a
                  ;; host effect that says exactly what should be scheduled.
                  (emit-effect {:type "animation.scheduleSnippet"
                                :agency "blink"
                                :effectId (:name snippet-data)
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
