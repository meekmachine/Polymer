(ns polymer.lipsync.scheduler)

;; LipSync owns one small timer: marking the agency idle after an utterance-length
;; snippet should have finished. It does not remove runtime clips; Polymer
;; Animation owns snippet cleanup through the active clip handle/timer.

(def cleanup-buffer-ms 80)

(defn clear-timeout! [timer-atom]
  ;; Keep timeout cleanup local to the scheduler so the agency can stop/replace
  ;; utterances without knowing which timer implementation is active.
  (when-let [timer @timer-atom]
    (js/clearTimeout timer)
    (reset! timer-atom nil)))

(defn create-scheduler [{:keys [on-finished]}]
  (let [finish-timer (atom nil)
        disposed? (atom false)]
    {:schedule-finished
     (fn [max-time-sec]
       ;; A LipSync utterance is scheduled as one animation snippet. This timer
       ;; only updates LipSync's own "speaking" state after that snippet should
       ;; have ended; Animation still owns clip cleanup and runtime handles.
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
       ;; Replacement/explicit stop cancels the pending finish callback. The
       ;; agency decides whether an animation remove request should also be sent.
       (clear-timeout! finish-timer))

     :dispose
     (fn []
       ;; Disposal is final for this scheduler instance; late callbacks are
       ;; guarded by disposed? even if a host implementation fails to clear.
       (reset! disposed? true)
       (clear-timeout! finish-timer))}))
