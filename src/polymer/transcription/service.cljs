(ns polymer.transcription.service
  (:require [clojure.string :as str]
            [polymer.transcription.agency :as transcription-agency]))

;; TranscriptionService owns the browser SpeechRecognition side effect for the
;; old LoomLarge module API. Recognition callbacks are converted into Polymer
;; Transcription agency commands and emitted to service subscribers as plain
;; transcript facts.
;;
;; Echo prevention and barge-in follow the Latticework runtime:
;; 1. Own the mic via getUserMedia with AEC/NS/AGC (+ voiceIsolation when available)
;; 2. Prefer track-based recognition.start(micTrack)
;; 3. Compare mic RMS against the agent playback reference track while speaking
;; 4. Emit onInterruption when the user exceeds the reference-aware threshold

(defn data-map [value]
  (cond
    (map? value) value
    value (js->clj value :keywordize-keys true)
    :else {}))

(defn js-call [target method-name & args]
  (when target
    (when-let [method (aget target method-name)]
      (when (fn? method)
        (.apply method target (to-array args))))))

(defn default-config [config]
  (merge {:lang "en-US"
          :continuous true
          :interimResults true
          :maxAlternatives 1
          :agentFilteringEnabled true
          :interruptDetectionEnabled true
          :requireAgentReferenceForInterruption true
          :interruptionDebugLogging false
          :interruptionVolumeThreshold 0.035
          :interruptionReferenceScale 0.45
          :interruptionReferenceOffset 0.015
          :interruptionHoldMs 150}
         (data-map config)))

(defn recognition-constructor []
  (let [win (when (exists? js/window) js/window)]
    (or (when win (aget win "SpeechRecognition"))
        (when win (aget win "webkitSpeechRecognition")))))

(defn emit-set! [listeners & args]
  (doseq [listener @listeners]
    (.apply listener nil (to-array args))))

(defn word-list [text]
  (->> (str/split (str/lower-case (or text "")) #"\s+")
       (remove str/blank?)
       set))

(defn word-tokens [text]
  (->> (str/split (str/lower-case (or text "")) #"\s+")
       (remove str/blank?)
       vec))

(defn boundary-events [text]
  (->> (str/split (or text "") #"\s+")
       (remove str/blank?)
       (map-indexed (fn [index word]
                      {:word word
                       :index index
                       :timestamp (.now js/Date)
                       :speaker "user"}))))

(defn read-level [analyser data]
  (if-not analyser
    0
    (do
      (.getByteTimeDomainData analyser data)
      (let [length (.-length data)]
        (loop [index 0
               sum 0]
          (if (< index length)
            (let [sample (/ (- (aget data index) 128) 128)]
              (recur (inc index) (+ sum (* sample sample))))
            (js/Math.sqrt (/ sum (max 1 length)))))))))

(defn create-service [config callbacks]
  (let [config-atom (atom (default-config config))
        state-atom (atom {:status "idle"
                          :currentTranscript nil
                          :isFinal false
                          :error nil})
        agency (transcription-agency/create-transcription-agency (clj->js @config-atom))
        recognition (atom nil)
        manual-stop? (atom false)
        transcript-listeners (atom #{})
        boundary-listeners (atom #{})
        interruption-listeners (atom #{})
        agent-speaking? (atom false)
        agent-words (atom #{})
        agent-script (atom "")
        disposed? (atom false)
        mic-stream (atom nil)
        analysis-context (atom nil)
        mic-analyser (atom nil)
        mic-analyser-data (atom nil)
        mic-source-node (atom nil)
        agent-reference-track (atom nil)
        agent-analyser (atom nil)
        agent-analyser-data (atom nil)
        agent-source-node (atom nil)
        agent-reference-update-token (atom 0)
        interruption-frame (atom nil)
        interruption-candidate-start (atom nil)
        interruption-latched? (atom false)
        last-interruption-debug-at (atom 0)]
    (letfn [(set-state! [updates]
              (swap! state-atom merge updates))
            (dispatch! [command]
              (js-call agency "dispatch" (clj->js command)))
            (filtered-agent-echo? [text]
              (and (:agentFilteringEnabled @config-atom)
                   @agent-speaking?
                   (let [tokens (word-tokens text)
                         words (set tokens)
                         overlap (count (filter @agent-words words))
                         all-match? (and (seq tokens)
                                         (every? @agent-words tokens))
                         prefix-match? (str/starts-with? @agent-script (str/lower-case text))]
                     (or all-match?
                         prefix-match?
                         (and (seq words)
                              (pos? overlap)
                              (>= (/ overlap (count words)) 0.65))))))
            (emit-transcript! [text final?]
              (when-not (filtered-agent-echo? text)
                (set-state! {:status (if final? "processing" "listening")
                             :currentTranscript text
                             :isFinal final?
                             :error nil})
                (dispatch! {:type (if final? "finalTranscript" "partialTranscript")
                            :text text
                            :isFinal final?
                            :source "webSpeech"})
                (js-call callbacks "onTranscript" text final?)
                (emit-set! transcript-listeners text final?)
                (when final?
                  (doseq [event (boundary-events text)]
                    (js-call callbacks "onBoundary" (clj->js event))
                    (emit-set! boundary-listeners (clj->js event))))))
            (handle-result! [event]
              (let [results (.-results event)
                    start (or (.-resultIndex event) 0)
                    total (.-length results)]
                (loop [index start]
                  (when (< index total)
                    (let [result (aget results index)
                          alternative (aget result 0)
                          text (str/trim (or (.-transcript alternative) ""))
                          final? (boolean (.-isFinal result))]
                      (when (and (pos? (count text))
                                 (or final? (:interimResults @config-atom)))
                        (emit-transcript! text final?)))
                    (recur (inc index))))))
            (handle-error! [event]
              (let [message (or (.-error event) "speech-recognition-error")
                    error (js/Error. message)]
                (when-not (and @manual-stop? (= message "aborted"))
                  (set-state! {:status "error" :error message})
                  (dispatch! {:type "error" :message message})
                  (js-call callbacks "onError" error))))
            (handle-end! []
              (set-state! {:status "idle"})
              (dispatch! {:type "stop"})
              (js-call callbacks "onEnd"))
            (ensure-recognition! []
              (or @recognition
                  (when-let [ctor (recognition-constructor)]
                    (let [instance (ctor.)]
                      (set! (.-lang instance) (:lang @config-atom))
                      (set! (.-continuous instance) (boolean (:continuous @config-atom)))
                      (set! (.-interimResults instance) (boolean (:interimResults @config-atom)))
                      (set! (.-maxAlternatives instance) (:maxAlternatives @config-atom))
                      (set! (.-onstart instance)
                            (fn []
                              (reset! manual-stop? false)
                              (set-state! {:status "listening" :error nil})
                              (dispatch! {:type "start"})
                              (js-call callbacks "onStart")))
                      (set! (.-onresult instance) handle-result!)
                      (set! (.-onerror instance) handle-error!)
                      (set! (.-onend instance) handle-end!)
                      (reset! recognition instance)
                      instance))))
            (ensure-analysis-context! []
              (js/Promise.
               (fn [resolve reject]
                 (try
                   (when (or (nil? @analysis-context)
                             (= "closed" (.-state @analysis-context)))
                     (reset! analysis-context (js/AudioContext.)))
                   (let [ctx @analysis-context]
                     (if (= "suspended" (.-state ctx))
                       (-> (.resume ctx)
                           (.then (fn [_] (resolve ctx)))
                           (.catch (fn [error]
                                     (js/console.warn "[TranscriptionService] Failed to resume analysis context:" error)
                                     (resolve ctx))))
                       (resolve ctx)))
                   (catch :default error
                     (reject error))))))
            (ensure-microphone-analyser! []
              (-> (ensure-analysis-context!)
                  (.then (fn [ctx]
                           (when-let [stream @mic-stream]
                             (let [track (aget (.getAudioTracks stream) 0)
                                   existing-source ^js @mic-source-node
                                   existing-track (when existing-source
                                                    (aget (.getAudioTracks (.-mediaStream existing-source)) 0))]
                               (when (and track
                                          (or (nil? existing-source)
                                              (not= track existing-track)))
                                 (when existing-source
                                   (try (.disconnect existing-source) (catch :default _ nil)))
                                 (when @mic-analyser
                                   (try (.disconnect @mic-analyser) (catch :default _ nil)))
                                 (let [source (.createMediaStreamSource ctx stream)
                                       analyser (.createAnalyser ctx)]
                                   (set! (.-fftSize analyser) 2048)
                                   (set! (.-smoothingTimeConstant analyser) 0.65)
                                   (.connect source analyser)
                                   (reset! mic-source-node source)
                                   (reset! mic-analyser analyser)
                                   (reset! mic-analyser-data (js/Uint8Array. (.-fftSize analyser)))))))))))
            (ensure-microphone-stream! []
              (js/Promise.
               (fn [resolve reject]
                 (let [existing @mic-stream
                       live? (and existing
                                  (some #(= "live" (.-readyState %))
                                        (array-seq (.getAudioTracks existing))))]
                   (if live?
                     (-> (ensure-microphone-analyser!)
                         (.then (fn [_] (resolve existing)))
                         (.catch reject))
                     (let [media (.-mediaDevices js/navigator)
                           supported (if (and media (.-getSupportedConstraints media))
                                       (.getSupportedConstraints media)
                                       #js {})
                           constraints (clj->js (cond-> {:echoCancellation true
                                                         :noiseSuppression true
                                                         :autoGainControl true}
                                                  (boolean (aget supported "voiceIsolation"))
                                                  (assoc :voiceIsolation true)))]
                       (js/console.log "[TranscriptionService] Requesting microphone permission...")
                       (-> (.getUserMedia media #js {:audio constraints})
                           (.catch (fn [error]
                                     (js/console.warn "[TranscriptionService] Enhanced mic constraints failed, retrying with plain audio:" error)
                                     (.getUserMedia media #js {:audio true})))
                           (.then (fn [stream]
                                    (reset! mic-stream stream)
                                    (js/console.log "[TranscriptionService] Microphone permission granted")
                                    (-> (ensure-microphone-analyser!)
                                        (.then (fn [_] (resolve stream))))))
                           (.catch reject))))))))
            (update-agent-reference-analyser! []
              (let [token (swap! agent-reference-update-token inc)
                    reference-track @agent-reference-track]
                (when @agent-source-node
                  (try (.disconnect @agent-source-node) (catch :default _ nil)))
                (when @agent-analyser
                  (try (.disconnect @agent-analyser) (catch :default _ nil)))
                (reset! agent-source-node nil)
                (reset! agent-analyser nil)
                (reset! agent-analyser-data nil)
                (when (and reference-track (= "live" (.-readyState reference-track)))
                  (-> (ensure-analysis-context!)
                      (.then (fn [ctx]
                               (when (and (= token @agent-reference-update-token)
                                          (= reference-track @agent-reference-track)
                                          (= "live" (.-readyState reference-track)))
                                 (let [stream (js/MediaStream. #js [reference-track])
                                       source (.createMediaStreamSource ctx stream)
                                       analyser (.createAnalyser ctx)]
                                   (set! (.-fftSize analyser) 2048)
                                   (set! (.-smoothingTimeConstant analyser) 0.65)
                                   (.connect source analyser)
                                   (if (and (= token @agent-reference-update-token)
                                            (= reference-track @agent-reference-track)
                                            (= "live" (.-readyState reference-track)))
                                     (do
                                       (reset! agent-source-node source)
                                       (reset! agent-analyser analyser)
                                       (reset! agent-analyser-data (js/Uint8Array. (.-fftSize analyser))))
                                     (do
                                       (.disconnect source)
                                       (.disconnect analyser)))))))))))
            (stop-interruption-monitoring! []
              (when-let [frame @interruption-frame]
                (.cancelAnimationFrame js/window frame)
                (reset! interruption-frame nil))
              (reset! interruption-candidate-start nil))
            (emit-interruption! [event]
              (js-call callbacks "onInterruption" event)
              (emit-set! interruption-listeners event))
            (start-interruption-monitoring! []
              (when (and (:interruptDetectionEnabled @config-atom)
                         (nil? @interruption-frame))
                (letfn [(tick []
                          (if-not @agent-speaking?
                            (stop-interruption-monitoring!)
                            (let [has-reference? (and @agent-reference-track
                                                      (= "live" (.-readyState @agent-reference-track))
                                                      @agent-analyser)]
                              (if (and (:requireAgentReferenceForInterruption @config-atom)
                                       (not has-reference?))
                                (do
                                  (reset! interruption-candidate-start nil)
                                  (reset! interruption-frame (.requestAnimationFrame js/window tick)))
                                (let [mic-level (read-level @mic-analyser @mic-analyser-data)
                                      reference-level (if has-reference?
                                                        (read-level @agent-analyser @agent-analyser-data)
                                                        0)
                                      required-level (js/Math.max
                                                      (:interruptionVolumeThreshold @config-atom)
                                                      (+ (* reference-level (:interruptionReferenceScale @config-atom))
                                                         (:interruptionReferenceOffset @config-atom)))
                                      should-interrupt? (>= mic-level required-level)]
                                  (when (:interruptionDebugLogging @config-atom)
                                    (let [now (.now js/performance)]
                                      (when (>= (- now @last-interruption-debug-at) 500)
                                        (reset! last-interruption-debug-at now)
                                        (js/console.debug "[TranscriptionService] Interruption levels"
                                                          #js {:microphoneLevel mic-level
                                                               :referenceLevel reference-level
                                                               :requiredLevel required-level
                                                               :shouldInterrupt should-interrupt?
                                                               :hasAgentReference has-reference?}))))
                                  (if (and should-interrupt? (not @interruption-latched?))
                                    (if (nil? @interruption-candidate-start)
                                      (reset! interruption-candidate-start (.now js/performance))
                                      (when (>= (- (.now js/performance) @interruption-candidate-start)
                                                (:interruptionHoldMs @config-atom))
                                        (reset! interruption-latched? true)
                                        (emit-interruption! #js {:timestamp (.now js/Date)
                                                                 :microphoneLevel mic-level
                                                                 :referenceLevel reference-level
                                                                 :requiredLevel required-level})))
                                    (when-not should-interrupt?
                                      (reset! interruption-candidate-start nil)))
                                  (reset! interruption-frame (.requestAnimationFrame js/window tick)))))))]
                  (reset! interruption-frame (.requestAnimationFrame js/window tick)))))
            (start-recognition! []
              (js/Promise.
               (fn [resolve reject]
                 (if-let [instance (ensure-recognition!)]
                   (-> (ensure-microphone-stream!)
                       (.then (fn [stream]
                                (let [track (aget (.getAudioTracks stream) 0)]
                                  (when-not track
                                    (throw (js/Error. "No microphone audio track available")))
                                  (try
                                    (js/console.log "[TranscriptionService] Starting speech recognition with mic track...")
                                    (.start instance track)
                                    (catch :default err
                                      (js/console.warn "[TranscriptionService] Track-based start failed, falling back to default start():" err)
                                      (.start instance)))
                                  (when @agent-speaking?
                                    (start-interruption-monitoring!))
                                  (resolve nil))))
                       (.catch reject))
                   (let [error (js/Error. "Web Speech recognition is not available")]
                     (handle-error! #js {:error (.-message error)})
                     (reject error))))))
            (prepare-agent-words! [text]
              (reset! agent-words (word-list text))
              (reset! agent-script (str/lower-case (or text ""))))]
      #js {:startListening (fn []
                             (if @disposed?
                               (js/Promise.reject (js/Error. "Transcription service is disposed"))
                               (if (= "listening" (:status @state-atom))
                                 (js/Promise.resolve nil)
                                 (do
                                   (reset! manual-stop? false)
                                   (start-recognition!)))))
           :stopListening (fn []
                            (reset! manual-stop? true)
                            (stop-interruption-monitoring!)
                            (when-let [instance @recognition]
                              (try
                                (.stop instance)
                                (catch :default _ nil)))
                            (set-state! {:status "idle"})
                            (dispatch! {:type "stop"}))
           :handleAgentSpeech (fn [event]
                                (let [payload (data-map event)]
                                  (case (:type payload)
                                    "AGENT_SCRIPT"
                                    (prepare-agent-words! (or (:phrase payload)
                                                              (str/join " " (:words payload))))

                                    "AGENT_START"
                                    (do
                                      (reset! agent-speaking? true)
                                      (reset! interruption-latched? false)
                                      (start-interruption-monitoring!))

                                    ("END" "AGENT_DONE" "PLAYBACK_ENDED")
                                    (do
                                      (reset! agent-speaking? false)
                                      (reset! agent-words #{})
                                      (reset! agent-script "")
                                      (reset! interruption-latched? false)
                                      (stop-interruption-monitoring!))

                                    "WORD"
                                    (swap! agent-words conj (str/lower-case (or (:word payload) "")))

                                    nil)))
           :prepareAgentSpeech (fn [text]
                                 ;; Prepare echo filter words only. Do not mark the
                                 ;; agent as speaking until real playback starts.
                                 (prepare-agent-words! text))
           :notifyAgentSpeech (fn [text]
                                (prepare-agent-words! text)
                                (reset! agent-speaking? true)
                                (reset! interruption-latched? false)
                                (reset! interruption-candidate-start nil)
                                (start-interruption-monitoring!))
           :notifyAgentSpeechEnd (fn []
                                   (reset! agent-speaking? false)
                                   (reset! agent-words #{})
                                   (reset! agent-script "")
                                   (reset! interruption-latched? false)
                                   (stop-interruption-monitoring!))
           :setAgentAudioReferenceTrack (fn [track]
                                          (reset! agent-reference-track track)
                                          (update-agent-reference-analyser!))
           :onBoundary (fn [listener]
                         (swap! boundary-listeners conj listener)
                         (fn [] (swap! boundary-listeners disj listener)))
           :onTranscript (fn [listener]
                           (swap! transcript-listeners conj listener)
                           (fn [] (swap! transcript-listeners disj listener)))
           :onInterruption (fn [listener]
                             (swap! interruption-listeners conj listener)
                             (fn [] (swap! interruption-listeners disj listener)))
           :getState (fn [] (clj->js @state-atom))
           :updateConfig (fn [updates]
                           (swap! config-atom merge (data-map updates))
                           (dispatch! {:type "configure" :config @config-atom})
                           (when-let [instance @recognition]
                             (set! (.-lang instance) (:lang @config-atom))
                             (set! (.-continuous instance) (boolean (:continuous @config-atom)))
                             (set! (.-interimResults instance) (boolean (:interimResults @config-atom)))
                             (set! (.-maxAlternatives instance) (:maxAlternatives @config-atom))))
           :dispose (fn []
                      (when-not @disposed?
                        (reset! disposed? true)
                        (reset! manual-stop? true)
                        (stop-interruption-monitoring!)
                        (when-let [instance @recognition]
                          (try
                            (.abort instance)
                            (catch :default _ nil)))
                        (when-let [stream @mic-stream]
                          (doseq [track (array-seq (.getTracks stream))]
                            (.stop track)))
                        (reset! mic-stream nil)
                        (when @mic-source-node
                          (try (.disconnect @mic-source-node) (catch :default _ nil)))
                        (when @mic-analyser
                          (try (.disconnect @mic-analyser) (catch :default _ nil)))
                        (when @agent-source-node
                          (try (.disconnect @agent-source-node) (catch :default _ nil)))
                        (when @agent-analyser
                          (try (.disconnect @agent-analyser) (catch :default _ nil)))
                        (when-let [ctx @analysis-context]
                          (try (.close ctx) (catch :default _ nil)))
                        (reset! analysis-context nil)
                        (reset! transcript-listeners #{})
                        (reset! boundary-listeners #{})
                        (reset! interruption-listeners #{})
                        (js-call agency "dispose")))})))

(defn TranscriptionService
  ([] (create-service nil nil))
  ([config] (create-service config nil))
  ([config callbacks] (create-service config callbacks)))

(defn createTranscriptionService
  ([] (TranscriptionService nil nil))
  ([config] (TranscriptionService config nil))
  ([config callbacks] (TranscriptionService config callbacks)))
