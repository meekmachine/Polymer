(ns polymer.blink.scheduler
  (:require [polymer.blink.planner :as planner]
            [polymer.blink.snippet :as snippet]))

;; The scheduler is the only Blink namespace that owns timers.
;;
;; It still does not call animation engines directly. When a blink should play,
;; it emits an animation request event. The character system routes that request
;; to Polymer's Animation agency, and Animation emits the host effect.

(defn clear-timeout! [timer-atom]
  (when-let [timer @timer-atom]
    (js/clearTimeout timer)
    (reset! timer-atom nil)))

(defn create-scheduler [{:keys [state-atom emit-event record-plan!]}]
  (let [auto-timer (atom nil)
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

            (trigger! [reason options]
              (when-not @disposed?
                (let [plan (planner/make-plan @state-atom reason options)
                      snippet-data (snippet/build-blink-snippet plan)]
                  (record-plan! plan)
                  ;; Blink requests animation as a domain event. Keeping this
                  ;; as an event lets the character-level agency network route
                  ;; it to Polymer Animation instead of letting Blink talk to the
                  ;; host animation service directly.
                  (emit-event {:type "animation.requestScheduleSnippet"
                               :agency "blink"
                               :requestId (:name snippet-data)
                               :snippet snippet-data
                               :options {:autoPlay true}})
                  (emit-plan! plan snippet-data nil)
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
                  (clear-timeout! auto-timer))})))
