(ns polymer.transcription.service
  (:require [clojure.string :as str]
            [polymer.transcription.agency :as transcription-agency]))

;; TranscriptionService owns the browser SpeechRecognition side effect for the
;; old LoomLarge module API. Recognition callbacks are converted into Polymer
;; Transcription agency commands and emitted to service subscribers as plain
;; transcript facts.

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
          :agentFilteringEnabled false
          :interruptDetectionEnabled false}
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

(defn boundary-events [text]
  (->> (str/split (or text "") #"\s+")
       (remove str/blank?)
       (map-indexed (fn [index word]
                      {:word word
                       :index index
                       :timestamp (.now js/Date)
                       :speaker "user"}))))

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
        disposed? (atom false)]
    (letfn [(set-state! [updates]
              (swap! state-atom merge updates))
            (dispatch! [command]
              (js-call agency "dispatch" (clj->js command)))
            (filtered-agent-echo? [text]
              (and (:agentFilteringEnabled @config-atom)
                   @agent-speaking?
                   (let [words (word-list text)
                         overlap (count (filter @agent-words words))]
                     (and (seq words)
                          (pos? overlap)
                          (>= (/ overlap (count words)) 0.65)))))
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
                      (when (or final? (:interimResults @config-atom))
                        (emit-transcript! text final?)))
                    (recur (inc index))))))
            (handle-error! [event]
              (let [message (or (.-error event) "speech-recognition-error")
                    error (js/Error. message)]
                (set-state! {:status "error" :error message})
                (dispatch! {:type "error" :message message})
                (js-call callbacks "onError" error)))
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
                      instance))))]
      #js {:startListening (fn []
                             (js/Promise.
                              (fn [resolve reject]
                                (if @disposed?
                                  (reject (js/Error. "Transcription service is disposed"))
                                  (if-let [instance (ensure-recognition!)]
                                    (try
                                      (.start instance)
                                      (resolve nil)
                                      (catch :default error
                                        (reject error)))
                                    (let [error (js/Error. "Web Speech recognition is not available")]
                                      (handle-error! #js {:error (.-message error)})
                                      (reject error)))))))
           :stopListening (fn []
                            (reset! manual-stop? true)
                            (when-let [instance @recognition]
                              (try
                                (.stop instance)
                                (catch :default _ nil)))
                            (set-state! {:status "idle"})
                            (dispatch! {:type "stop"}))
           :handleAgentSpeech (fn [event]
                                (let [payload (data-map event)]
                                  (case (:type payload)
                                    ("AGENT_SCRIPT" "AGENT_START")
                                    (do
                                      (reset! agent-speaking? true)
                                      (reset! agent-words (word-list (or (:phrase payload)
                                                                         (str/join " " (:words payload))))))

                                    ("END" "AGENT_DONE" "PLAYBACK_ENDED")
                                    (do
                                      (reset! agent-speaking? false)
                                      (reset! agent-words #{}))

                                    "WORD"
                                    (swap! agent-words conj (str/lower-case (or (:word payload) "")))

                                    nil)))
           :prepareAgentSpeech (fn [text]
                                 (reset! agent-speaking? true)
                                 (reset! agent-words (word-list text)))
           :notifyAgentSpeech (fn [text]
                                (reset! agent-speaking? true)
                                (swap! agent-words into (word-list text)))
           :notifyAgentSpeechEnd (fn []
                                   (reset! agent-speaking? false)
                                   (reset! agent-words #{}))
           :setAgentAudioReferenceTrack (fn [_track] nil)
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
                        (when-let [instance @recognition]
                          (try
                            (.abort instance)
                            (catch :default _ nil)))
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
