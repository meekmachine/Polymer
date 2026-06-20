(ns polymer.vocal.agency
  (:require [polymer.stream :as stream]
            [polymer.vocal.azure :as azure]
            [polymer.vocal.scheduler :as scheduler]
            [polymer.vocal.snippet :as snippet]
            [polymer.vocal.state :as state]
            [polymer.vocal.visemes :as visemes]))

;; Vocal/LipSync accepts provider/text timing data and produces animation intent.
;; It does not own audio playback, provider credentials, HTTP, LiveKit, React,
;; storage, or DOM APIs. Those are host-side side effects that feed commands
;; into this agency. Inside Polymer, the character network routes Vocal's
;; animation requests to the Animation agency.

(defn js-command [type value]
  #js {:type type :value value})

(defn normalize-timeline [timeline config]
  (let [input (or timeline {})
        source (or (:source input) "external")
        text (:text input)
        viseme-events (snippet/normalize-events (:visemes input))]
    {:name (:name input)
     :text text
     :source source
     :visemes viseme-events
     :wordTimings (state/normalize-word-timings (:wordTimings input))
     :durationSec (state/number-or (:durationSec input)
                                   (if (empty? viseme-events)
                                     0
                                     (apply max (map #(/ (+ (:offsetMs %) (:durationMs %)) 1000) viseme-events))))
     :config config}))

(defn timeline-name [timeline]
  (or (:name timeline)
      (str "vocal_timeline_" (.now js/Date))))

(defn create-vocal-agency [config]
  (let [input-stream (stream/create-stream)
        event-stream (stream/create-stream)
        effect-stream (stream/create-stream)
        state-atom (atom (state/config->state config))
        disposed? (atom false)
        emit-input (:emit input-stream)
        emit-event (:emit event-stream)
        agency-scheduler (atom nil)]
    (letfn [(active-name []
              (:snippetName @state-atom))

            (emit-animation-schedule! [snippet-data]
              ;; This is a domain event, not a host side-effect callback. The
              ;; Polymer character network consumes it and dispatches directly
              ;; to Polymer Animation.
              (emit-event {:type "animation.requestScheduleSnippet"
                           :agency "vocal"
                           :requestId (:name snippet-data)
                           :snippet snippet-data
                           :options {:autoPlay true}}))

            (emit-animation-remove! [name reason]
              (emit-event {:type "animation.requestRemoveSnippet"
                           :agency "vocal"
                           :requestId name
                           :name name
                           :reason reason}))

            (emit-animation-seek! [name offset-sec reason]
              (emit-event {:type "animation.requestSeekSnippet"
                           :agency "vocal"
                           :requestId (str name ":seek:" (.now js/Date))
                           :name name
                           :offsetSec offset-sec
                           :reason reason}))

            (stop-local! [reason remove?]
              (when-let [name (active-name)]
                (when remove?
                  (emit-animation-remove! name reason)))
              (when-let [s @agency-scheduler]
                ((:stop s)))
              (let [stopped-at (state/now-ms)]
                (swap! state-atom state/record-stop stopped-at reason)
                (emit-event {:type "vocalTimelineStopped"
                             :agency "vocal"
                             :reason reason
                             :stoppedAt stopped-at})))

            (finish-local! []
              (when (:speaking @state-atom)
                (stop-local! "completed" false)))

            (start-timeline! [timeline]
              (let [config (:config @state-atom)
                    normalized (normalize-timeline timeline config)]
                (if (empty? (:visemes normalized))
                  (emit-event {:type "error"
                               :agency "vocal"
                               :message "Vocal timeline requires at least one viseme event"})
                  (do
                    ;; One active utterance at a time keeps lip sync from
                    ;; accumulating stale viseme snippets.
                    (when (:speaking @state-atom)
                      (stop-local! "replaced" true))
                    (let [name (timeline-name normalized)
                          built (if (:text normalized)
                                  (snippet/build-text-snippet (:text normalized)
                                                              (:visemes normalized)
                                                              config)
                                  (snippet/build-vocal-snippet (:visemes normalized)
                                                               config
                                                               name))
                          snippet-data (cond-> (assoc built :name (or (:name normalized) (:name built)))
                                         (state/finite-number? (:durationSec normalized))
                                         (assoc :maxTime (max (:maxTime built) (:durationSec normalized))))
                          started-at (state/now-ms)]
                      (swap! state-atom state/record-start normalized (:name snippet-data) started-at (:maxTime snippet-data))
                      (emit-event {:type "vocalTimelineStarted"
                                   :agency "vocal"
                                   :name (:name snippet-data)
                                   :source (:source normalized)
                                   :text (:text normalized)
                                   :visemeCount (count (:visemes normalized))
                                   :maxTime (:maxTime snippet-data)
                                   :startedAt started-at})
                      (emit-animation-schedule! snippet-data)
                      (when-let [s @agency-scheduler]
                        ((:schedule-finished s) (:maxTime snippet-data)))
                      snippet-data)))))

            (start-text! [payload]
              (let [text (:text payload)
                    speech-rate (get-in @state-atom [:config :speechRate])]
                (if (and (string? text) (pos? (count text)))
                  (start-timeline! {:name (:name payload)
                                    :text text
                                    :source (or (:source payload) "text")
                                    :visemes (visemes/text->visemes text speech-rate)
                                    :wordTimings (:wordTimings payload)})
                  (emit-event {:type "error"
                               :agency "vocal"
                               :message "Vocal startText command requires text"}))))

            (process-azure! [payload]
              (let [options (merge {:wordTimings (:wordTimings payload)
                                    :visualLeadMs (get-in @state-atom [:config :visualLeadMs])}
                                   (:options payload))
                    timeline (azure/azure-visemes->timeline (:visemes payload)
                                                            (:totalDurationMs payload)
                                                            options)]
                (start-timeline! {:name (:name payload)
                                  :text (:text payload)
                                  :source (or (:source payload) "azure")
                                  :visemes timeline
                                  :wordTimings (:wordTimings options)
                                  :durationSec (when (state/finite-number? (:totalDurationMs payload))
                                                 (/ (:totalDurationMs payload) 1000))})))

            (handle-word-boundary! [payload]
              (let [word (:word payload)
                    word-index (int (state/number-or (:wordIndex payload) (:wordIndex @state-atom)))
                    observed-at (state/now-ms)]
                (swap! state-atom state/record-word-boundary word word-index observed-at)
                (emit-event {:type "vocalWordBoundary"
                             :agency "vocal"
                             :word word
                             :wordIndex word-index
                             :observedAt observed-at})
                (let [current @state-atom
                      expected (get (:wordTimings current) word-index)
                      elapsed-sec (state/number-or (:observedElapsedSec payload)
                                                   (if (:startTime current)
                                                     (/ (- observed-at (:startTime current)) 1000)
                                                     0))
                      drift-sec (when expected (- elapsed-sec (:startSec expected)))
                      threshold (get-in current [:config :wordDriftThresholdSec])
                      name (:snippetName current)]
                  (when (and expected name drift-sec (> (js/Math.abs drift-sec) threshold))
                    (let [target-sec (min (:maxTime current) (max 0 elapsed-sec))
                          corrected-at (state/now-ms)]
                      (swap! state-atom state/record-sync-correction name target-sec corrected-at)
                      (emit-event {:type "vocalSyncDrift"
                                   :agency "vocal"
                                   :name name
                                   :word word
                                   :wordIndex word-index
                                   :expectedSec (:startSec expected)
                                   :observedSec elapsed-sec
                                   :driftSec drift-sec
                                   :targetSec target-sec
                                   :correctedAt corrected-at})
                      (emit-animation-seek! name target-sec "word-boundary-drift"))))))

            (update-word-timings! [payload]
              (let [updated-at (state/now-ms)
                    timings (:wordTimings payload)]
                (swap! state-atom state/record-word-timings timings updated-at)
                (emit-event {:type "vocalWordTimingsUpdated"
                             :agency "vocal"
                             :count (count (state/normalize-word-timings timings))
                             :updatedAt updated-at})))

            (dispatch! [command]
              (when-not @disposed?
                (let [payload (js->clj command :keywordize-keys true)
                      type (:type payload)]
                  (emit-input {:type "command"
                               :agency "vocal"
                               :command payload})
                  (case type
                    "configure"
                    (do
                      (swap! state-atom state/configure (:config payload))
                      (emit-event {:type "vocalConfigChanged"
                                   :agency "vocal"
                                   :state @state-atom}))

                    "startText"
                    (start-text! payload)

                    "startTimeline"
                    (start-timeline! (:timeline payload))

                    "processAzureVisemes"
                    (process-azure! payload)

                    "wordBoundary"
                    (handle-word-boundary! payload)

                    "updateWordTimings"
                    (update-word-timings! payload)

                    "stop"
                    (stop-local! "requested" true)

                    "reset"
                    (do
                      (stop-local! "reset" true)
                      (reset! state-atom (state/config->state nil))
                      (emit-event {:type "vocalConfigChanged"
                                   :agency "vocal"
                                   :state @state-atom}))

                    (emit-event {:type "error"
                                 :agency "vocal"
                                 :message (str "Unknown Vocal command: " type)})))))]
      (reset! agency-scheduler (scheduler/create-scheduler {:on-finished finish-local!}))
      #js {:dispatch dispatch!
           :snapshot (fn [] (state/visible-state @state-atom))
           :input (stream/writable-port input-stream dispatch!)
           :events (stream/readable-port event-stream)
           :effects (stream/readable-port effect-stream)
           :subscribeInput (fn [listener] ((:subscribe input-stream) listener))
           :subscribeEvents (fn [listener] ((:subscribe event-stream) listener))
           :subscribeEffects (fn [listener] ((:subscribe effect-stream) listener))
           :subscribe (fn [listener] ((:subscribe event-stream) listener))
           :subscribeStatus (fn [listener] ((:subscribe event-stream) listener))
           :subscribeCommands (fn [listener] ((:subscribe effect-stream) listener))
           :configure (fn [next-config] (dispatch! #js {:type "configure" :config next-config}))
           :startText (fn [text] (dispatch! #js {:type "startText" :text text}))
           :startTimeline (fn [timeline] (dispatch! #js {:type "startTimeline" :timeline timeline}))
           :processAzureVisemes (fn
                                  ([visemes] (dispatch! #js {:type "processAzureVisemes" :visemes visemes}))
                                  ([visemes total-duration-ms]
                                   (dispatch! #js {:type "processAzureVisemes"
                                                   :visemes visemes
                                                   :totalDurationMs total-duration-ms})))
           :wordBoundary (fn
                           ([word] (dispatch! #js {:type "wordBoundary" :word word}))
                           ([word word-index observed-elapsed-sec]
                            (dispatch! #js {:type "wordBoundary"
                                            :word word
                                            :wordIndex word-index
                                            :observedElapsedSec observed-elapsed-sec})))
           :updateWordTimings (fn [word-timings]
                                (dispatch! #js {:type "updateWordTimings"
                                                :wordTimings word-timings}))
           :stop (fn [] (dispatch! #js {:type "stop"}))
           :reset (fn [] (dispatch! #js {:type "reset"}))
           :dispose (fn []
                      (when-not @disposed?
                        (reset! disposed? true)
                        (when-let [s @agency-scheduler]
                          ((:dispose s)))
                        ((:dispose input-stream))
                        ((:dispose event-stream))
                        ((:dispose effect-stream))))})))
