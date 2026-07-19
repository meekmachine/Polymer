(ns polymer.blink.scheduler
  (:require [polymer.blink.planner :as planner]
            [polymer.blink.snippet :as snippet]))

;; The scheduler is the only Blink namespace that owns timers.
;;
;; It still does not call animation engines directly. When a blink should play,
;; it emits animation intent. The Polymer character network routes that intent
;; to Polymer Animation, and only Polymer Animation talks to the animation
;; runtime.

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
                ;; Cross-agency signals also live on the event stream. The
                ;; Polymer character network turns this signal into a small
                ;; prosodic animation cue until a dedicated prosodic agency
                ;; owns that behavior.
                (emit-event {:type "signal"
                             :agency "blink"
                             :signal "blink-fast"
                             :plan plan})))

            (trigger! [reason options random-value now-ms]
              (when-not @disposed?
                (let [plan (planner/make-plan @state-atom reason options random-value now-ms)
                      snippet-data (snippet/build-blink-snippet plan)]
                  (record-plan! plan)
                  ;; Blink requests animation as data. The Polymer agency
                  ;; network routes it to Animation instead of letting Blink
                  ;; touch the animation runtime.
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
                                 (when-let [snippet-data (trigger! "auto" nil (js/Math.random) (.now js/Date))]
                                   (let [extra-ms (max 0 (- (snippet/snippet-duration-ms snippet-data)
                                                           (* 1000 (:duration @state-atom))))]
                                     (schedule-next-auto! extra-ms))))
                               delay-ms)))))))]
      {:trigger trigger!
       :refresh-auto (fn [] (schedule-next-auto! 0))
       :stop (fn [] (clear-timeout! auto-timer))
       :dispose (fn []
                  (reset! disposed? true)
                  (clear-timeout! auto-timer))})))
