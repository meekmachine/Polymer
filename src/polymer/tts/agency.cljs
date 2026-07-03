(ns polymer.tts.agency
  (:require [clojure.string :as str]
            [polymer.stream :as stream]
            [polymer.tts.azure :as azure]
            [polymer.tts.planner :as planner]
            [polymer.tts.state :as state]))

;; TTS owns speech-provider side effects and emits plain data facts for LipSync.
;; Web Speech and Azure connection logic belongs here, not in LoomLarge React.

(def azure-audio-unlock-data-url
  ;; This silent WAV primes HTMLAudio during a user gesture before real Azure audio.
  "data:audio/wav;base64,UklGRsQAAABXQVZFZm10IBAAAAABAAEAgD4AAAB9AAACABAAZGF0YaAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA")

(defn now-ms
  "Read the best available monotonic-ish time for public events."
  []
  (if (and (exists? js/performance) (.-now js/performance))
    (.now js/performance)
    (.now js/Date)))

(defn window*
  "Return browser window when the package is running in a browser."
  []
  (when (exists? js/window)
    js/window))

(defn speech-synthesis*
  "Return the Web Speech synthesis object when available."
  []
  (when-let [window (window*)]
    (.-speechSynthesis window)))

(defn fetch*
  "Return the host fetch function from globalThis/window when available."
  []
  (or (when (exists? js/fetch) js/fetch)
      (when-let [window (window*)]
        (.-fetch window))))

(defn custom-provider
  "Read an injected provider function from config for tests or alternate hosts."
  [config key]
  (get-in config [:providers key]))

(defn js-promise?
  "Treat any thenable as a Promise for simple interop."
  [value]
  (and value (= "function" (goog/typeOf (aget value "then")))))

(defn promise-resolve
  "Normalize raw values and promises into one async path."
  [value]
  (if (js-promise? value)
    value
    (js/Promise.resolve value)))

(defn js-error
  "Create a JavaScript Error with a stable string message."
  [message]
  (js/Error. message))

(defn callback-event
  "Normalize callback payloads from browser code or injected JS test providers."
  [event]
  (if (map? event)
    event
    (js->clj event :keywordize-keys true)))

(defn plan-failure-message
  "Turn the first failed provider-plan step into a user/debug friendly error string."
  [plan]
  (let [step (first (:steps plan))
        reason (:reason step)
        engine (:engine step)]
    (case reason
      "missing-text" "TTS plan failed: missing text"
      "provider-not-ready" (str "TTS plan failed: provider not ready"
                                (when engine (str " (" engine ")")))
      "unsupported-engine" (str "TTS plan failed: unsupported engine"
                                (when engine (str " (" engine ")")))
      (str "TTS plan failed: " reason))))

(defn scalar->azure-percent
  "Convert UI scalar rate/pitch into Azure SSML percent strings."
  [value]
  (let [percent (js/Math.round (* (- value 1) 100))]
    (if (pos? percent)
      (str "+" percent "%")
      (str percent "%"))))

(defn backend-url
  "Resolve backend URL from command first, then agency config."
  [config command]
  (state/string-or (:backendUrl command) (:backendUrl config)))

(defn json-request
  "Make a JSON request through the browser fetch API."
  [url options]
  (if-let [fetch-fn (fetch*)]
    (-> (.call fetch-fn js/globalThis url options)
        (.then (fn [response]
                 (if (.-ok response)
                   (.json response)
                   (throw (js-error (str "HTTP " (.-status response) " " (.-statusText response))))))))
    (js/Promise.reject (js-error "fetch is not available in this JavaScript runtime"))))

(defn post-json
  "POST a JSON body and parse the JSON response."
  [url body]
  (json-request url
                #js {:method "POST"
                     :headers #js {"Content-Type" "application/json"}
                     :body (.stringify js/JSON (clj->js body))}))

(defn get-json
  "GET JSON from a backend endpoint."
  [url]
  (json-request url #js {:method "GET"}))

(defn audio-mime-type
  "Infer an audio MIME type from Azure's response format string."
  [format]
  (let [raw (or format "")
        lower (.toLowerCase raw)]
    (cond
      (.startsWith raw "audio/") raw
      (or (.includes lower "mp3") (.includes lower "mpeg")) "audio/mpeg"
      (.includes lower "wav") "audio/wav"
      (.includes lower "ogg") "audio/ogg"
      :else "audio/mpeg")))

(defn base64-payload
  "Strip a data URL prefix so atob receives only base64 audio bytes."
  [audio-base64]
  (.replace (or audio-base64 "") #"^data:audio/[^;]+;base64," ""))

(defn base64->bytes
  "Decode base64 audio into a Uint8Array for Blob/AudioContext playback."
  [audio-base64]
  (let [binary (js/atob (base64-payload audio-base64))
        length (.-length binary)
        bytes (js/Uint8Array. length)]
    (doseq [index (range length)]
      (aset bytes index (.charCodeAt binary index)))
    bytes))

(defn base64->object-url
  "Create an object URL for Azure audio playback."
  [audio-base64 format]
  (js/URL.createObjectURL
   (js/Blob. #js [(base64->bytes audio-base64)]
             #js {:type (audio-mime-type format)})))

(defn prime-audio!
  "Prime one HTMLAudio element while the user gesture is still fresh."
  [resources volume]
  (when (exists? js/Audio)
    (let [audio (or (:audio @resources) (js/Audio.))]
      (swap! resources assoc :audio audio)
      (set! (.-muted audio) true)
      (set! (.-loop audio) true)
      (set! (.-volume audio) volume)
      (set! (.-src audio) azure-audio-unlock-data-url)
      (let [play-result (.play audio)]
        (when (js-promise? play-result)
          (.catch play-result (fn [_error] nil))))
      audio)))

(defn reset-primed-audio!
  "Reset the primed audio element before attaching real Azure audio."
  [audio volume]
  (.pause audio)
  (set! (.-loop audio) false)
  (set! (.-muted audio) false)
  (set! (.-volume audio) volume)
  (set! (.-onended audio) nil)
  (set! (.-onerror audio) nil)
  (.removeAttribute audio "src")
  (.load audio))

(defn extract-boundary-word
  "Recover a word from Web Speech charIndex callbacks."
  [text char-index]
  (let [safe-index (max 0 (min (count text) (or char-index 0)))
        tail (.slice text safe-index)
        forward (.match tail #"[A-Za-z']+")]
    (if (and forward (aget forward 0))
      (aget forward 0)
      (let [head (.slice text 0 safe-index)
            backward (.match head #"[A-Za-z']+$")]
        (or (when backward (aget backward 0)) "")))))

(defn js-voices->clj
  "Convert browser SpeechSynthesisVoice objects into plain Clojure maps."
  [voices]
  (mapv (fn [voice]
          {:id (.-name voice)
           :name (.-name voice)
           :language (.-lang voice)
           :gender ""
           :styles []})
        (array-seq voices)))

(defn normalize-voice
  "Normalize Web Speech/Azure voice objects into one UI-friendly shape."
  [voice provider]
  {:id (or (:id voice) (:name voice) (:shortName voice) "")
   :name (or (:name voice) (:localName voice) (:id voice) "")
   :language (or (:language voice) (:lang voice) (:locale voice) "")
   :gender (or (:gender voice) "")
   :styles (vec (or (:styles voice) []))
   :provider provider})

(defn voice-xf
  "Build the provider-specific voice normalization transducer."
  [provider]
  ;; Voice loading is a pure provider-boundary cleanup step. Keeping the map/filter
  ;; as a local transducer gives one pass over provider lists without hiding any
  ;; HTTP, browser, or runtime side effects inside the transform.
  (comp
   (map #(normalize-voice % provider))
   (filter #(pos? (count (:id %))))))

(defn normalize-voices
  "Normalize voice choices and drop empty placeholder rows."
  [voices provider]
  (into [] (voice-xf provider) (or voices [])))

(defn azure-cache-key
  "Build a stable Azure synthesis cache key from actual synthesis inputs."
  [text voice-name style rate pitch]
  (pr-str [(str/trim (or text "")) voice-name style rate pitch]))

(defn remember-cache
  "Store one Azure synthesis result and evict oldest entries past the limit."
  [cache key result limit]
  (let [without-key (dissoc cache key)
        next-cache (assoc without-key key result)
        overflow (- (count next-cache) limit)]
    (if (pos? overflow)
      (apply dissoc next-cache (take overflow (keys next-cache)))
      next-cache)))

(defn cleanup-audio!
  "Stop and release the active audio element/object URL resources."
  [resources]
  (when-let [audio (:audio @resources)]
    (.pause audio)
    (set! (.-onended audio) nil)
    (set! (.-onerror audio) nil)
    (.removeAttribute audio "src"))
  (when-let [source (:audioSource @resources)]
    (try
      (.stop source)
      (catch :default _ nil)))
  (when-let [url (:audioUrl @resources)]
    (js/URL.revokeObjectURL url))
  (when-let [frame (:boundaryFrame @resources)]
    (when-let [window (window*)]
      (.cancelAnimationFrame window frame)))
  (swap! resources assoc
         :audio nil
         :audioUrl nil
         :audioSource nil
         :boundaryFrame nil
         :webSpeechFallbackTimer nil))

(defn schedule-boundaries!
  "Emit word boundaries against a playback clock without a React render loop."
  [resources session-id active-session? clock word-timings on-boundary]
  (let [index (atom 0)
        tick (atom nil)]
    (reset! tick
            (fn []
              (when (and (active-session? session-id) ((:shouldContinue clock)))
                (let [current-time ((:currentTime clock))]
                  (while (and (< @index (count word-timings))
                              (<= (:startSec (nth word-timings @index)) (+ current-time 0.02)))
                    (let [boundary (nth word-timings @index)]
                      (on-boundary {:word (:word boundary)
                                    :observedElapsedSec current-time})
                      (swap! index inc))))
                (when-let [window (window*)]
                  (swap! resources assoc :boundaryFrame (.requestAnimationFrame window @tick))))))
    (when-let [window (window*)]
      (swap! resources assoc :boundaryFrame (.requestAnimationFrame window @tick)))))

(defn play-html-audio!
  "Play Azure audio through HTMLAudio and report a clock once playback begins."
  [resources audio-base64 audio-format volume]
  (let [audio (or (:audio @resources) (js/Audio.))
        url (base64->object-url audio-base64 audio-format)]
    (reset-primed-audio! audio volume)
    (swap! resources assoc :audio audio :audioUrl url)
    (set! (.-preload audio) "auto")
    (set! (.-volume audio) volume)
    (set! (.-src audio) url)
    (.load audio)
    (-> (.play audio)
        (.then (fn []
                 {:usingHtmlAudio true
                  :durationSec (if (state/finite-number? (.-duration audio)) (.-duration audio) 0)
                  :clock {:currentTime (fn [] (.-currentTime audio))
                          :shouldContinue (fn [] (and (not (.-paused audio)) (not (.-ended audio))))}
                  :audio audio})))))

(defn play-azure-audio!
  "Use an injected audio player when present, otherwise use browser HTMLAudio."
  [config resources plan]
  (if-let [custom (custom-provider config :azurePlayAudio)]
    (promise-resolve (custom (clj->js plan)))
    (play-html-audio! resources
                      (:audioBase64 plan)
                      (:audioFormat plan)
                      (:volume plan))))

(defn load-web-speech-voices!
  "Load browser Web Speech voices, using an injected provider in tests."
  [config]
  (if-let [custom (custom-provider config :webSpeechVoices)]
    (promise-resolve (custom))
    (if-let [synthesis (speech-synthesis*)]
      (js/Promise.
       (fn [resolve _reject]
         (letfn [(finish []
                   (resolve (clj->js (js-voices->clj (.getVoices synthesis)))))]
           (if (pos? (.-length (.getVoices synthesis)))
             (finish)
             (do
               (set! (.-onvoiceschanged synthesis) finish)
               (when-let [window (window*)]
                 (.setTimeout window finish 500)))))))
      (js/Promise.reject (js-error "Web Speech synthesis is not available")))))

(defn load-azure-voices!
  "Ask the configured backend to validate Azure and return available voices."
  [config]
  (if-let [custom (custom-provider config :azureVoices)]
    (promise-resolve (custom))
    (let [base (:backendUrl config)]
      (if (pos? (count base))
        (-> (post-json (str base "/api/azure-tts/connect") {})
            (.then (fn [response]
                     (let [data (js->clj response :keywordize-keys true)]
                       (if (and (:valid data) (seq (:voices data)))
                         data
                         (-> (get-json (str base "/api/azure-tts/voices"))
                             (.then (fn [fallback]
                                      (assoc (js->clj fallback :keywordize-keys true)
                                             :valid false
                                             :message "Backend Azure credentials are not configured.")))))))))
        (js/Promise.reject (js-error "Azure backendUrl is not configured"))))))

(defn synthesize-azure!
  "Request Azure synthesis through Polymer-owned backend protocol."
  [config command]
  (if-let [custom (custom-provider config :azureSynthesize)]
    (promise-resolve (custom (clj->js command)))
    (let [base (backend-url config command)]
      (if (pos? (count base))
        (post-json (str base "/api/azure-tts/synthesize")
                   {:text (:text command)
                    :voice_name (or (:voiceName command) (:azureVoiceName config))
                    :style (let [style (or (:style command) (:azureStyle config))]
                             (when (pos? (count style)) style))
                    :rate (scalar->azure-percent (or (:rate command) (:rate config)))
                    :pitch (scalar->azure-percent (or (:pitch command) (:pitch config)))})
        (js/Promise.reject (js-error "Azure backendUrl is not configured"))))))

(defn speak-web-speech!
  "Start browser Web Speech playback or an injected test provider."
  [config plan]
  (cond
    (custom-provider config :webSpeechSpeak)
    (promise-resolve ((custom-provider config :webSpeechSpeak) (clj->js plan)))

    (speech-synthesis*)
    (let [synthesis (speech-synthesis*)
          window (window*)
          utterance (js/SpeechSynthesisUtterance. (:text plan))
          voice-name (:voiceName plan)
          voices (.getVoices synthesis)
          matching-voice (some #(when (= voice-name (.-name %)) %) (array-seq voices))
          visuals-started (atom false)
          visual-started-at (atom nil)
          fallback-timer (atom nil)
          clear-fallback (fn []
                           (when @fallback-timer
                             (.clearTimeout window @fallback-timer)
                             (reset! fallback-timer nil)))
          start-visuals (fn []
                          (when-not @visuals-started
                            (reset! visuals-started true)
                            (reset! visual-started-at (now-ms))
                            (clear-fallback)
                            ((:onAudioStarted plan) {:currentTimeSec 0
                                                     :durationSec 0
                                                     :usingHtmlAudio false})))]
      (when matching-voice
        (set! (.-voice utterance) matching-voice))
      (set! (.-rate utterance) (:rate plan))
      (set! (.-pitch utterance) (:pitch plan))
      (set! (.-volume utterance) (:volume plan))
      (set! (.-onstart utterance) start-visuals)
      (set! (.-onboundary utterance)
            (fn [^js event]
              (start-visuals)
              (when (or (not (.-name event)) (= "word" (.-name event)))
                ((:onBoundary plan)
                 {:word (extract-boundary-word (:text plan) (.-charIndex event))
                  :observedElapsedSec (.-elapsedTime event)
                  :hostElapsedSec (when @visual-started-at
                                    (/ (- (now-ms) @visual-started-at) 1000))}))))
      (set! (.-onend utterance)
            (fn []
              (clear-fallback)
              ((:onEnd plan))))
      (set! (.-onerror utterance)
            (fn [^js event]
              (clear-fallback)
              ((:onError plan) (js-error (str "Web Speech failed: " (.-error event))))))
      (.speak synthesis utterance)
      (reset! fallback-timer
              (.setTimeout window
                           (fn []
                             (when (.-speaking synthesis)
                               (start-visuals)))
                           120))
      (js/Promise.resolve #js {:stop (fn [] (.cancel synthesis))}))

    :else
    (js/Promise.reject (js-error "Web Speech synthesis is not available"))))

(defn create-tts-agency
  "Create a TTS agency that owns provider sessions and emits LipSync facts."
  [config]
  (let [initial-config (js->clj config :keywordize-keys true)
        input-stream (stream/create-stream)
        event-stream (stream/create-stream)
        effect-stream (stream/create-stream)
        emit-input (:emit input-stream)
        emit-event (:emit event-stream)
        state-atom (atom (state/default-state initial-config))
        resources (atom {:audio nil
                         :audioUrl nil
                         :audioSource nil
                         :boundaryFrame nil
                         :webSpeechHandle nil})
        azure-cache (atom {})
        disposed? (atom false)]
    (letfn [(session-id []
                ;; Every provider callback captures the session id active when
                ;; it was registered. Replacements/stops increment the token so
                ;; late callbacks cannot mutate state or emit stale LipSync data.
              (:sessionId @state-atom))

            (active-session? [id]
              (and (not @disposed?) (= id (session-id))))

            (emit-status! []
                ;; Status events are coarse UI facts, not animation ticks. The
                ;; agency never asks React to render for each viseme/word frame.
              (emit-event {:type "ttsStatusChanged"
                           :agency "tts"
                           :state (state/visible-state @state-atom)}))

            (emit-error! [message]
              (swap! state-atom state/record-error message)
              (emit-event {:type "error" :agency "tts" :message message})
              (emit-status!))

            (emit-lipsync! [command]
                ;; This is an event-stream bridge inside Polymer. The character
                ;; network routes this command to LipSync, which then emits
                ;; animation intent for the Animation agency.
              (emit-event {:type "lipSync.command"
                           :agency "tts"
                           :command command}))

            (configure-lipsync! [engine command]
                ;; TTS knows provider timing and user speech controls; LipSync knows
                ;; how those controls become mouth/jaw/tongue animation.
              (let [config (:config @state-atom)]
                (emit-lipsync! {:type "configure"
                                :config {:intensity (:lipsyncIntensity config)
                                         :jawScale (:jawScale config)
                                         :speechRate (or (:rate command) (:rate config))
                                         :visualLeadMs (or (:visualLeadMs command) (:visualLeadMs config))
                                         :wordDriftThresholdSec (if (= engine "azure")
                                                                  (:azureDriftThresholdSec config)
                                                                  (:webSpeechDriftThresholdSec config))}})))

            (stop-session! [reason]
              (cleanup-audio! resources)
              (when-let [handle (:webSpeechHandle @resources)]
                (when-let [stop (aget handle "stop")]
                  (stop)))
              (swap! resources assoc :webSpeechHandle nil)
              (emit-lipsync! {:type "stop" :reason reason})
              (swap! state-atom state/record-stopped (now-ms))
              (emit-event {:type "ttsSpeechStopped"
                           :agency "tts"
                           :reason reason
                           :stoppedAt (:endedAt @state-atom)})
              (emit-status!))

            (finish-session! [id]
              (when (active-session? id)
                (stop-session! "completed")
                (emit-event {:type "ttsSpeechEnded"
                             :agency "tts"
                             :endedAt (:endedAt @state-atom)})))

            (word-boundary! [id word observed-elapsed-sec host-elapsed-sec]
              (when (and (active-session? id) (pos? (count (or word ""))))
                (let [word-index (:wordIndex @state-atom)]
                  (swap! state-atom state/record-word-boundary word-index)
                  (emit-lipsync! {:type "wordBoundary"
                                  :word word
                                  :wordIndex word-index
                                  :observedElapsedSec observed-elapsed-sec
                                  :hostElapsedSec host-elapsed-sec})
                  (emit-event {:type "ttsWordBoundary"
                               :agency "tts"
                               :word word
                               :wordIndex word-index
                               :observedElapsedSec observed-elapsed-sec
                               :hostElapsedSec host-elapsed-sec}))))

            (start-web-speech! [id command snippet-name]
              (let [config (:config @state-atom)
                    text (:text command)
                    plan {:text text
                          :voiceName (or (:voiceName command) (:voiceName config))
                          :rate (or (:rate command) (:rate config))
                          :pitch (or (:pitch command) (:pitch config))
                          :volume (or (:volume command) (:volume config))
                          :onAudioStarted (fn [_event]
                                            (when (active-session? id)
                                              (configure-lipsync! "webSpeech" command)
                                              (emit-lipsync! {:type "startText"
                                                              :name snippet-name
                                                              :text text
                                                              :source "webSpeech"})
                                              (emit-lipsync! {:type "audioStarted"
                                                              :name snippet-name
                                                              :audioTimeSec 0})
                                              (swap! state-atom state/record-speaking (now-ms))
                                              (emit-event {:type "ttsSpeechStarted"
                                                           :agency "tts"
                                                           :engine "webSpeech"
                                                           :name snippet-name
                                                           :startedAt (:startedAt @state-atom)})
                                              (emit-status!)))
                          :onBoundary (fn [event]
                                        (let [payload (callback-event event)]
                                          (word-boundary! id
                                                          (:word payload)
                                                          (:observedElapsedSec payload)
                                                          (:hostElapsedSec payload))))
                          :onEnd (fn [] (finish-session! id))
                          :onError (fn [error] (emit-error! (.-message error)))}]
                (-> (speak-web-speech! config plan)
                    (.then (fn [handle]
                             (when (active-session? id)
                               (swap! resources assoc :webSpeechHandle handle))))
                    (.catch (fn [error]
                              (when (active-session? id)
                                (emit-error! (.-message error))))))))

            (start-azure! [id command snippet-name]
              (let [config (:config @state-atom)
                    text (:text command)
                    voice-name (or (:voiceName command) (:azureVoiceName config))
                    style (or (:style command) (:azureStyle config))
                    rate (or (:rate command) (:rate config))
                    pitch (or (:pitch command) (:pitch config))
                    volume (or (:volume command) (:volume config))
                    cache-key (azure-cache-key text voice-name style (scalar->azure-percent rate) (scalar->azure-percent pitch))
                    cached (get @azure-cache cache-key)
                    synth-promise (if cached
                                    (js/Promise.resolve (clj->js cached))
                                    (synthesize-azure! config (assoc command
                                                                     :voiceName voice-name
                                                                     :style style
                                                                     :rate rate
                                                                     :pitch pitch)))]
                  ;; Prime synchronously while the click/user gesture is still
                  ;; available. Synthesis may finish later, but the element is
                  ;; already allowed to play in stricter browsers.
                (prime-audio! resources volume)
                (-> synth-promise
                    (.then (fn [raw]
                               ;; Provider/backend shapes are normalized before
                               ;; any playback or LipSync command is emitted.
                             (let [payload (azure/normalize-azure-synthesis (js->clj raw :keywordize-keys true))]
                               (when-not cached
                                 (swap! azure-cache remember-cache cache-key payload (:azureCacheLimit config)))
                               (if (and (:audioBase64 payload) (seq (:visemes payload)))
                                 payload
                                 (throw (js-error "Azure TTS returned no usable audio or viseme payload"))))))
                    (.then (fn [payload]
                             (when (active-session? id)
                               (play-azure-audio! config resources
                                                  (assoc payload
                                                         :volume volume)))))
                    (.then (fn [playback]
                             (when (active-session? id)
                               (let [payload (if cached cached (get @azure-cache cache-key))
                                     duration-sec (or (:durationSec payload) (aget playback "durationSec") 0)
                                     word-timings (:wordTimings payload)]
                                 (configure-lipsync! "azure" command)
                                 (emit-lipsync! (azure/azure-synthesis->lipsync-command
                                                 snippet-name
                                                 text
                                                 (assoc payload :durationSec duration-sec)
                                                 config))
                                 (emit-lipsync! {:type "audioStarted"
                                                 :name snippet-name
                                                 :audioTimeSec 0})
                                 (swap! state-atom state/record-speaking (now-ms))
                                 (emit-event {:type "ttsSpeechStarted"
                                              :agency "tts"
                                              :engine "azure"
                                              :name snippet-name
                                              :startedAt (:startedAt @state-atom)})
                                 (emit-status!)
                                 (when-let [clock (or (:clock playback)
                                                      (when (aget playback "clock")
                                                        (js->clj (aget playback "clock") :keywordize-keys true)))]
                                   (schedule-boundaries! resources id active-session? clock word-timings
                                                         (fn [event]
                                                           (word-boundary! id
                                                                           (:word event)
                                                                           (:observedElapsedSec event)
                                                                           nil))))
                                 (when-let [audio (or (:audio playback) (aget playback "audio"))]
                                   (set! (.-onended audio) #(finish-session! id)))))))
                    (.catch (fn [error]
                              (when (active-session? id)
                                (emit-error! (.-message error))))))))

            (speak! [command]
              (let [config (:config @state-atom)
                    engine (or (:engine command) (:engine config))
                    text (:text command)
                    snippet-name (or (:name command) (str "tts_" engine "_" (.now js/Date)))
                    ;; Provider planning sees only capability facts. It does not
                    ;; receive JS handles or secrets, and it never performs side
                    ;; effects.
                    world {:backendUrl (backend-url config command)
                           :hasAzureSynthesize (boolean (custom-provider config :azureSynthesize))
                           :hasWebSpeech (boolean (or (custom-provider config :webSpeechSpeak)
                                                      (speech-synthesis*)))}
                    plan (planner/plan-speech (assoc command :engine engine) config world)]
                (swap! state-atom (fn [state]
                                    (-> state
                                        state/next-session
                                        (state/record-plan plan)
                                        (state/record-loading engine text snippet-name))))
                (emit-event {:type "ttsPlanCreated"
                             :agency "tts"
                             :plan plan})
                (emit-status!)
                (if-not (:ok plan)
                  (emit-error! (plan-failure-message plan))
                  (case engine
                    "azure" (start-azure! (session-id) command snippet-name)
                    "webSpeech" (start-web-speech! (session-id) command snippet-name)
                    (emit-error! (str "Unsupported TTS engine: " engine))))))

            (load-voices! [engine]
              (let [config (:config @state-atom)
                      ;; Voice loading has its own plan so missing backend/browser
                      ;; support is visible before any provider request is made.
                    world {:backendUrl (:backendUrl config)
                           :hasAzureVoices (boolean (custom-provider config :azureVoices))
                           :hasWebSpeech (boolean (or (custom-provider config :webSpeechVoices)
                                                      (speech-synthesis*)))}
                    plan (planner/plan-voice-load engine world)]
                (emit-event {:type "ttsPlanCreated"
                             :agency "tts"
                             :plan plan})
                (if-not (:ok plan)
                  (do
                    (when (= "azure" engine)
                      (swap! state-atom
                             state/record-azure-status
                             "error"
                             (plan-failure-message plan)
                             []))
                    (emit-error! (plan-failure-message plan)))
                  (case engine
                    "webSpeech"
                    (-> (load-web-speech-voices! config)
                        (.then (fn [voices]
                                 (let [normalized (normalize-voices
                                                   (js->clj voices :keywordize-keys true)
                                                   "webSpeech")]
                                   (swap! state-atom state/record-web-speech-voices normalized)
                                   (emit-event {:type "ttsVoicesLoaded"
                                                :agency "tts"
                                                :engine "webSpeech"
                                                :voices normalized})
                                   (emit-status!))))
                        (.catch (fn [error] (emit-error! (.-message error)))))

                    "azure"
                    (-> (load-azure-voices! config)
                        (.then (fn [response]
                                 (let [data (js->clj response :keywordize-keys true)
                                       voices (normalize-voices (:voices data) "azure")
                                       ready? (and (:valid data) (seq voices))
                                       status (if ready? "ready" "error")
                                       message (if ready?
                                                 (str "Connected via backend environment (" (count voices) " voices).")
                                                 (or (:message data) "Backend Azure credentials are not configured."))]
                                   (swap! state-atom state/record-azure-status status message voices)
                                   (emit-event {:type "ttsVoicesLoaded"
                                                :agency "tts"
                                                :engine "azure"
                                                :voices voices
                                                :status status
                                                :message message})
                                   (emit-status!))))
                        (.catch (fn [error]
                                  (swap! state-atom state/record-azure-status "error" (.-message error) [])
                                  (emit-error! (.-message error)))))

                    (emit-error! (str "Unsupported voice provider: " engine))))))

            (dispatch! [command]
              (when-not @disposed?
                (let [payload (js->clj command :keywordize-keys true)]
                  (emit-input {:type "command"
                               :agency "tts"
                               :command payload})
                  (case (:type payload)
                    "configure"
                    (do
                      (swap! state-atom state/configure (:config payload))
                      (emit-status!))

                    "loadVoices"
                    (load-voices! (or (:engine payload) (:engine (:config @state-atom))))

                    "speak"
                    (do
                      (when (:speaking @state-atom)
                        (stop-session! "replaced"))
                      (speak! payload))

                    "stop"
                    (do
                      (swap! state-atom state/next-session)
                      (stop-session! "requested"))

                    "reset"
                    (do
                      (swap! state-atom state/next-session)
                      (cleanup-audio! resources)
                      (reset! state-atom (state/default-state nil))
                      (emit-status!))

                    (emit-error! (str "Unknown TTS command: " (:type payload)))))))]
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
           :loadVoices (fn
                         ([] (dispatch! #js {:type "loadVoices"}))
                         ([engine] (dispatch! #js {:type "loadVoices" :engine engine})))
           :speak (fn [text] (dispatch! #js {:type "speak" :text text}))
           :stop (fn [] (dispatch! #js {:type "stop"}))
           :reset (fn [] (dispatch! #js {:type "reset"}))
           :dispose (fn []
                      (when-not @disposed?
                        (reset! disposed? true)
                        (cleanup-audio! resources)
                        ((:dispose input-stream))
                        ((:dispose event-stream))
                        ((:dispose effect-stream))))})))
