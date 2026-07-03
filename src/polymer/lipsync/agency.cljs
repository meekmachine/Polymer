(ns polymer.lipsync.agency
  (:require [polymer.lipsync.goap :as goap]
            [polymer.stream :as stream]
            [polymer.tts.azure :as azure]
            [polymer.lipsync.scheduler :as scheduler]
            [polymer.lipsync.articulation.snippet :as snippet]
            [polymer.lipsync.state :as state]
            [polymer.lipsync.articulation.visemes :as visemes]))

;; LipSync accepts provider/text timing data and produces animation intent.
;; It does not own audio playback, provider credentials, HTTP, LiveKit, React,
;; storage, or DOM APIs. Those are host-side side effects that feed commands
;; into this agency. Inside Polymer, the character network routes LipSync's
;; animation requests to the Animation agency.

(defn js-command [type value]
  #js {:type type :value value})

(defn plan-failure-message
  "Turn the failed LipSync plan step into a readable domain error."
  [plan]
  (let [step (first (:steps plan))]
    (case (:reason step)
      "unsupported-command" (str "lipSync plan failed: unsupported command " (:commandType step))
      "missing-text" "lipSync plan failed: startText requires text"
      "missing-timeline-visemes" "lipSync plan failed: startTimeline requires visemes"
      "missing-provider-visemes" "lipSync plan failed: processAzureVisemes requires provider visemes"
      "missing-word" "lipSync plan failed: wordBoundary requires a word"
      (str "lipSync plan failed: " (:reason step)))))

(defn visual-lead-ms [input config]
  (state/clamp 0 250 (state/number-or (:visualLeadMs input) (:visualLeadMs config))))

(defn apply-visual-lead [events lead-ms]
  (let [lead (max 0 (state/number-or lead-ms 0))]
    (mapv (fn [event]
            (update event :offsetMs #(max 0 (- % lead))))
          events)))

(defn normalize-timeline [timeline config]
  (let [input (or timeline {})
        source (or (:source input) "external")
        text (:text input)
        lead-ms (visual-lead-ms input config)
        viseme-events (apply-visual-lead (snippet/normalize-events (:visemes input)) lead-ms)]
    {:name (:name input)
     :text text
     :source source
     :visemes viseme-events
     :visualLeadMs lead-ms
     :wordTimings (state/normalize-word-timings (:wordTimings input))
     :durationSec (state/number-or (:durationSec input)
                                   (if (empty? viseme-events)
                                     0
                                     (apply max (map #(/ (+ (:offsetMs %) (:durationMs %)) 1000) viseme-events))))
     :config config}))

(defn normalize-observed-elapsed-sec [value expected max-time]
  ;; Web Speech boundary clocks are not consistent enough to trust blindly
  ;; across browsers. The spec says seconds, but some engines/adapters have
  ;; reported millisecond-looking values. Treat a value as milliseconds only
  ;; when it is clearly farther from the expected word start than value/1000.
  ;; Without this guard, the first nonzero boundary can seek a whole utterance
  ;; clip to its end, which looks like one lip movement at the beginning.
  (when (state/finite-number? value)
    (let [raw (max 0 value)
          millis (/ raw 1000)
          expected-start (when expected (:startSec expected))
          raw-error (when (state/finite-number? expected-start)
                      (js/Math.abs (- raw expected-start)))
          millis-error (when (state/finite-number? expected-start)
                         (js/Math.abs (- millis expected-start)))
          max-time (max 0 (state/number-or max-time 0))
          within-utterance? (or (zero? max-time)
                                (<= millis (+ max-time 2)))
          expected-says-ms? (and (> raw 10)
                                 raw-error
                                 millis-error
                                 (< millis-error raw-error)
                                 within-utterance?)
          max-time-says-ms? (and (> raw (+ max-time 2))
                                 (> raw 50)
                                 within-utterance?)]
      (if (or expected-says-ms? max-time-says-ms?)
        millis
        raw))))

(defn choose-boundary-elapsed-sec [payload expected max-time]
  ;; Prefer Web Speech's elapsedTime after unit normalization. Some browsers
  ;; report 0 for later word boundaries, so LoomLarge also sends a monotonic
  ;; host clock. Use that host clock only when the provider clock is clearly not
  ;; advancing; otherwise provider timing remains the authority.
  (let [observed (normalize-observed-elapsed-sec (:observedElapsedSec payload)
                                                 expected
                                                 max-time)
        host (when (state/finite-number? (:hostElapsedSec payload))
               (max 0 (:hostElapsedSec payload)))
        expected-start (when expected (:startSec expected))]
    (cond
      (and host
           observed
           (state/finite-number? expected-start)
           (> expected-start 0.08)
           (< observed 0.001)
           (> host 0.001))
      host

      observed
      observed

      host
      host

      :else
      nil)))

(defn payload-duration-sec [payload]
  (or (when (state/finite-number? (:durationSec payload))
        (max 0 (:durationSec payload)))
      (when (state/finite-number? (:totalDurationMs payload))
        (/ (max 0 (:totalDurationMs payload)) 1000))))

(defn timing-duration-sec [word-timings]
  (when (seq word-timings)
    (apply max 0 (map :endSec (state/normalize-word-timings word-timings)))))

(defn prepare-text-timeline [text speech-rate payload]
  (let [source (or (:source payload) "text")
        events (visemes/text->visemes text speech-rate)
        generated-word-timings (visemes/text->word-timings text speech-rate)
        supplied-word-timings (state/normalize-word-timings (:wordTimings payload))
        base-duration-ms (visemes/timeline-duration-ms events)
        explicit-duration-sec (or (payload-duration-sec payload)
                                  (timing-duration-sec supplied-word-timings))
        web-speech-duration-sec (when (= "webSpeech" source)
                                  (/ (visemes/web-speech-duration-ms text speech-rate base-duration-ms)
                                     1000))
        target-duration-sec (or explicit-duration-sec
                                web-speech-duration-sec
                                (/ base-duration-ms 1000))
        base-duration-sec (/ base-duration-ms 1000)
        scale (if (and (pos? base-duration-sec)
                       (state/finite-number? target-duration-sec)
                       (> target-duration-sec 0))
                (/ target-duration-sec base-duration-sec)
                1)
        should-scale? (> (js/Math.abs (- scale 1)) 0.02)
        word-timings (if (seq supplied-word-timings)
                       supplied-word-timings
                       (if should-scale?
                         (visemes/scale-word-timings generated-word-timings scale)
                         generated-word-timings))]
    {:name (:name payload)
     :text text
     :source source
     :visemes (if should-scale?
                (visemes/scale-events events scale)
                events)
     :wordTimings word-timings
     :durationSec (if (state/finite-number? target-duration-sec)
                    target-duration-sec
                    base-duration-sec)}))

(defn timeline-name [timeline]
  (or (:name timeline)
      (str "lipSync_timeline_" (.now js/Date))))

(defn create-lipsync-agency [config]
  (let [input-stream (stream/create-stream)
        event-stream (stream/create-stream)
        effect-stream (stream/create-stream)
        state-atom (atom (state/config->state config))
        disposed? (atom false)
        emit-input (:emit input-stream)
        emit-event (:emit event-stream)
        agency-scheduler (atom nil)]
    ;; Stream contract for this agency:
    ;; - input records accepted commands for tests/workers/debugging.
    ;; - events carry domain facts and cross-agency requests.
    ;; - effects is intentionally empty today; Animation, not LoomLarge, owns
    ;;   runtime side effects once the character network routes event data.
    (letfn [(active-name []
              (:snippetName @state-atom))

            (emit-animation-schedule! [snippet-data]
              ;; This is a domain event, not a host side-effect callback. The
              ;; Polymer character network consumes it and dispatches directly
              ;; to Polymer Animation.
              (emit-event {:type "animation.requestScheduleSnippet"
                           :agency "lipSync"
                           :requestId (:name snippet-data)
                           :snippet snippet-data
                           :options {:autoPlay true}}))

            (emit-animation-remove! [name reason]
              (emit-event {:type "animation.requestRemoveSnippet"
                           :agency "lipSync"
                           :requestId name
                           :name name
                           :reason reason}))

            (emit-animation-seek! [name offset-sec reason]
              (emit-event {:type "animation.requestSeekSnippet"
                           :agency "lipSync"
                           :requestId (str name ":seek:" (.now js/Date))
                           :name name
                           :offsetSec offset-sec
                           :reason reason}))

            (audio-clock-sec [payload]
              (max 0
                   (state/number-or (or (:audioTimeSec payload)
                                        (:currentTimeSec payload)
                                        (:offsetSec payload))
                                    0)))

            (stop-local! [reason remove?]
              (when-let [name (active-name)]
                (when remove?
                  (emit-animation-remove! name reason)))
              (when-let [s @agency-scheduler]
                ((:stop s)))
              (let [stopped-at (state/now-ms)]
                (swap! state-atom state/record-stop stopped-at reason)
                (emit-event {:type "lipSyncTimelineStopped"
                             :agency "lipSync"
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
                               :agency "lipSync"
                               :message "lipSync timeline requires at least one viseme event"})
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
                                  (snippet/build-lipsync-snippet (:visemes normalized)
                                                                 config
                                                                 name))
                          ;; Keep one snippet as the atomic animation unit for
                          ;; an utterance. Individual visemes, jaw, and tongue
                          ;; gestures are channels inside the snippet rather
                          ;; than separately scheduled clips.
                          snippet-data (cond-> (assoc built :name (or (:name normalized) (:name built)))
                                         (state/finite-number? (:durationSec normalized))
                                         (assoc :maxTime (max (:maxTime built) (:durationSec normalized))))
                          started-at (state/now-ms)]
                      (swap! state-atom state/record-start normalized (:name snippet-data) started-at (:maxTime snippet-data))
                      (emit-event {:type "lipSyncTimelineStarted"
                                   :agency "lipSync"
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
                  (start-timeline! (prepare-text-timeline text speech-rate payload))
                  (emit-event {:type "error"
                               :agency "lipSync"
                               :message "lipSync startText command requires text"}))))

            (process-azure! [payload]
              ;; Compatibility command for Azure/LiveKit provider facts. The
              ;; Azure-specific viseme-id mapping lives in TTS; LipSync only
              ;; normalizes word timing metadata and then uses the shared
              ;; articulated timeline path.
              (let [raw-options (or (:options payload) {})
                    word-timings (state/normalize-word-timings
                                  (or (:wordTimings payload)
                                      (:wordTimings raw-options)))
                    options (assoc (merge {:visualLeadMs (get-in @state-atom [:config :visualLeadMs])}
                                          raw-options)
                                   :wordTimings word-timings)
                    timeline (azure/azure-visemes->timeline (:visemes payload)
                                                            (:totalDurationMs payload)
                                                            options)]
                (start-timeline! {:name (:name payload)
                                  :text (:text payload)
                                  :source (or (:source payload) "azure")
                                  :visemes timeline
                                  :visualLeadMs (:visualLeadMs options)
                                  :wordTimings (:wordTimings options)
                                  :durationSec (when (state/finite-number? (:totalDurationMs payload))
                                                 (/ (:totalDurationMs payload) 1000))})))

            (handle-word-boundary! [payload]
              (let [word (:word payload)
                    word-index (int (state/number-or (:wordIndex payload) (:wordIndex @state-atom)))
                    observed-at (state/now-ms)]
                (swap! state-atom state/record-word-boundary word word-index observed-at)
                (emit-event {:type "lipSyncWordBoundary"
                             :agency "lipSync"
                             :word word
                             :wordIndex word-index
                             :observedAt observed-at})
                (let [current @state-atom
                      expected (get (:wordTimings current) word-index)
                      observed-elapsed-sec (choose-boundary-elapsed-sec payload
                                                                        expected
                                                                        (:maxTime current))
                      elapsed-sec (state/number-or observed-elapsed-sec
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
                      (emit-event {:type "lipSyncSyncDrift"
                                   :agency "lipSync"
                                   :name name
                                   :word word
                                   :wordIndex word-index
                                   :expectedSec (:startSec expected)
                                   :observedSec elapsed-sec
                                   :driftSec drift-sec
                                   :targetSec target-sec
                                   :correctedAt corrected-at})
                      (emit-animation-seek! name target-sec "word-boundary-drift"))))))

            (handle-audio-started! [payload]
              ;; The host owns audio playback. This command tells LipSync when
              ;; that side effect actually started so Animation can align the
              ;; scheduled snippet to the audio clock without LoomLarge calling
              ;; Animation directly.
              (let [current @state-atom
                    name (or (:name payload) (:snippetName current))
                    audio-time-sec (min (:maxTime current) (audio-clock-sec payload))
                    observed-at (state/now-ms)]
                (swap! state-atom state/record-audio-started audio-time-sec observed-at)
                (emit-event {:type "lipSyncAudioStarted"
                             :agency "lipSync"
                             :name name
                             :audioTimeSec audio-time-sec
                             :observedAt observed-at})
                (when name
                  (emit-animation-seek! name audio-time-sec "audio-started"))))

            (handle-audio-time! [payload]
              ;; Optional low-frequency host clock correction. This is not a
              ;; frame loop; callers should send it only when a material drift
              ;; correction is needed.
              (let [current @state-atom
                    name (or (:name payload) (:snippetName current))
                    audio-time-sec (min (:maxTime current) (audio-clock-sec payload))
                    observed-at (state/now-ms)]
                (swap! state-atom state/record-audio-time audio-time-sec observed-at)
                (emit-event {:type "lipSyncAudioTime"
                             :agency "lipSync"
                             :name name
                             :audioTimeSec audio-time-sec
                             :observedAt observed-at})
                (when name
                  (emit-animation-seek! name audio-time-sec "audio-time"))))

            (update-word-timings! [payload]
              (let [updated-at (state/now-ms)
                    timings (:wordTimings payload)]
                (swap! state-atom state/record-word-timings timings updated-at)
                (emit-event {:type "lipSyncWordTimingsUpdated"
                             :agency "lipSync"
                             :count (count (state/normalize-word-timings timings))
                             :updatedAt updated-at})))

            (dispatch! [command]
              (when-not @disposed?
                (let [payload (js->clj command :keywordize-keys true)
                      type (:type payload)
                      plan (goap/plan-command payload {:speaking (:speaking @state-atom)})]
                  ;; Every command produces an input echo and a plan event before
                  ;; any mutation. That makes failed/ignored commands observable
                  ;; without asking React to subscribe to mutable state.
                  (emit-input {:type "command"
                               :agency "lipSync"
                               :command payload})
                  (emit-event {:type "lipSyncPlanCreated"
                               :agency "lipSync"
                               :plan plan})
                  (if-not (:ok plan)
                    (emit-event {:type "error"
                                 :agency "lipSync"
                                 :message (plan-failure-message plan)})
                    (case type
                      "configure"
                      (do
                        (swap! state-atom state/configure (:config payload))
                        (emit-event {:type "lipSyncConfigChanged"
                                     :agency "lipSync"
                                     :state @state-atom}))

                      "startText"
                      (start-text! payload)

                      "startTimeline"
                      (start-timeline! (:timeline payload))

                      "processAzureVisemes"
                      (process-azure! payload)

                      "wordBoundary"
                      (handle-word-boundary! payload)

                      "audioStarted"
                      (handle-audio-started! payload)

                      "audioTime"
                      (handle-audio-time! payload)

                      "updateWordTimings"
                      (update-word-timings! payload)

                      "stop"
                      (stop-local! "requested" true)

                      "reset"
                      (do
                        (stop-local! "reset" true)
                        (reset! state-atom (state/config->state nil))
                        (emit-event {:type "lipSyncConfigChanged"
                                     :agency "lipSync"
                                     :state @state-atom}))

                      (emit-event {:type "error"
                                   :agency "lipSync"
                                   :message (str "Unknown LipSync command: " type)}))))))]
      (reset! agency-scheduler (scheduler/create-scheduler {:on-finished finish-local!}))
      #js {:dispatch dispatch!
           :snapshot (fn [] (state/visible-state @state-atom))
           :input (stream/writable-port input-stream dispatch!)
           :events (stream/readable-port event-stream)
           ;; Empty compatibility stream. Cross-agency animation requests are
           ;; domain events so the Polymer character network can route them to
           ;; Animation without LoomLarge becoming the interpreter.
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
           :audioStarted (fn
                           ([] (dispatch! #js {:type "audioStarted"}))
                           ([audio-time-sec]
                            (dispatch! #js {:type "audioStarted"
                                            :audioTimeSec audio-time-sec})))
           :audioTime (fn [audio-time-sec]
                        (dispatch! #js {:type "audioTime"
                                        :audioTimeSec audio-time-sec}))
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
