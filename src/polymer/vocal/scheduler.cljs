(ns polymer.vocal.scheduler)

;; Vocal owns one small timer: marking the agency idle after an utterance-length
;; snippet should have finished. It does not remove runtime clips; Polymer
;; Animation owns snippet cleanup through the active clip handle/timer.

(def cleanup-buffer-ms 80)

(defn clear-timeout! [timer-atom]
  (when-let [timer @timer-atom]
    (js/clearTimeout timer)
    (reset! timer-atom nil)))

(defn create-scheduler [{:keys [on-finished]}]
  (let [finish-timer (atom nil)
        disposed? (atom false)]
    {:schedule-finished
     (fn [max-time-sec]
       (clear-timeout! finish-timer)
       (when-not @disposed?
         (when (and (number? max-time-sec) (js/isFinite max-time-sec) (pos? max-time-sec))
           (reset! finish-timer
                   (js/setTimeout
                    (fn []
                      (reset! finish-timer nil)
                      (when-not @disposed?
                        (on-finished)))
                    (+ (* max-time-sec 1000) cleanup-buffer-ms))))))

     :stop
     (fn []
       (clear-timeout! finish-timer))

     :dispose
     (fn []
       (reset! disposed? true)
       (clear-timeout! finish-timer))}))
