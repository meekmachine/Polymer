(ns polymer.tts.scheduler)

;; TTS owns provider and audio side effects, but session callbacks should still
;; be scheduled in one place. This scheduler queues Web Speech fallback-start
;; callbacks and Azure audio-clock word-boundary polling. It does not synthesize
;; speech, play audio, or call LipSync directly; the TTS agency supplies
;; callbacks that translate scheduled facts into ordinary TTS/LipSync events.

(defn window* []
  (when (exists? js/window)
    js/window))

(defn now-ms []
  (.now js/Date))

(defn clear-timeout! [timer]
  (when timer
    (when-let [window (window*)]
      (.clearTimeout window timer))))

(defn clear-frame! [frame]
  (when frame
    (when-let [window (window*)]
      (.cancelAnimationFrame window frame))))

(defn create-scheduler []
  (let [start-fallbacks (atom {})
        boundary-frames (atom {})
        queue (atom [])
        disposed? (atom false)]
    (letfn [(enqueue! [effect]
              (let [entry (assoc effect
                                 :queueIndex (count @queue)
                                 :queuedAt (now-ms))]
                (swap! queue conj entry)
                entry))

            (cancel-start-fallback! [session-id]
              (when-let [timer (get @start-fallbacks session-id)]
                (clear-timeout! timer)
                (swap! start-fallbacks dissoc session-id)))

            (cancel-boundaries! [session-id]
              (when-let [frame (get @boundary-frames session-id)]
                (clear-frame! frame)
                (swap! boundary-frames dissoc session-id)))

            (request-boundary-frame! [session-id tick]
              (when-let [window (window*)]
                (swap! boundary-frames assoc session-id (.requestAnimationFrame window tick))))

            (stop-session! [session-id]
              (cancel-start-fallback! session-id)
              (cancel-boundaries! session-id))]
      {:schedule-start-fallback
       (fn [session-id delay-ms callback]
         (when-not @disposed?
           (cancel-start-fallback! session-id)
           (enqueue! {:type "webSpeechStartFallback"
                      :agency "tts"
                      :sessionId session-id
                      :delayMs delay-ms})
           (when-let [window (window*)]
             (swap! start-fallbacks
                    assoc
                    session-id
                    (.setTimeout window
                                 (fn []
                                   (swap! start-fallbacks dissoc session-id)
                                   (when-not @disposed?
                                     (callback)))
                                 delay-ms)))))

       :cancel-start-fallback
       (fn [session-id]
         (cancel-start-fallback! session-id))

       :schedule-boundaries
       (fn [session-id active-session? clock word-timings on-boundary]
         (when-not @disposed?
           (cancel-boundaries! session-id)
           (enqueue! {:type "audioBoundaryPolling"
                      :agency "tts"
                      :sessionId session-id
                      :wordCount (count word-timings)})
           (let [index (atom 0)
                 tick (atom nil)]
             (reset! tick
                     (fn []
                       (if (and (not @disposed?)
                                (active-session? session-id)
                                ((:shouldContinue clock)))
                         (do
                           (let [current-time ((:currentTime clock))]
                             (while (and (< @index (count word-timings))
                                         (<= (:startSec (nth word-timings @index)) (+ current-time 0.02)))
                               (let [boundary (nth word-timings @index)]
                                 (on-boundary {:word (:word boundary)
                                               :observedElapsedSec current-time})
                                 (swap! index inc))))
                           (request-boundary-frame! session-id @tick))
                         (cancel-boundaries! session-id))))
             (request-boundary-frame! session-id @tick))))

       :stop-session
       (fn [session-id]
         (stop-session! session-id))

       :stop-all
       (fn []
         (doseq [session-id (keys @start-fallbacks)]
           (cancel-start-fallback! session-id))
         (doseq [session-id (keys @boundary-frames)]
           (cancel-boundaries! session-id)))

       :queue
       (fn []
         @queue)

       :dispose
       (fn []
         (reset! disposed? true)
         (doseq [timer (vals @start-fallbacks)]
           (clear-timeout! timer))
         (doseq [frame (vals @boundary-frames)]
           (clear-frame! frame))
         (reset! start-fallbacks {})
         (reset! boundary-frames {})
         (reset! queue []))})))
