(ns polymer.tts.runtime
  (:require [clojure.string :as str]
            [polymer.tts.state :as state]))

;; TTS runtime functions are the provider/audio boundary for the TTS agency.
;;
;; This namespace is intentionally impure: it is the place that talks to browser
;; APIs, backend HTTP endpoints, injected test providers, HTMLAudio, Blob URLs,
;; and Web Speech utterance callbacks. Keeping those functions out of
;; `polymer.tts.agency` lets the agency stay focused on command planning,
;; session state, and plain event emission to LipSync.

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

(defn js-callable?
  "Return true when a JS interop value can be called."
  [value]
  (= "function" (goog/typeOf value)))

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
  (swap! resources assoc
         :audio nil
         :audioUrl nil
         :audioSource nil
         :webSpeechFallbackTimer nil))

(defn stop-web-speech-handle!
  "Cancel a Web Speech handle returned by the browser or an injected provider."
  [handle]
  (when-let [stop (and handle (aget handle "stop"))]
    (when (js-callable? stop)
      (.call stop handle))))

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
                   (resolve (clj->js (mapv (fn [voice]
                                              {:id (.-name voice)
                                               :name (.-name voice)
                                               :language (.-lang voice)
                                               :gender ""
                                               :styles []})
                                            (array-seq (.getVoices synthesis))))))]
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
          clear-fallback (fn []
                           (when-let [cancel (:cancelStartFallback plan)]
                             (cancel)))
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
      (if-let [schedule-fallback (:scheduleStartFallback plan)]
        (schedule-fallback 120
                           (fn []
                             (when (.-speaking synthesis)
                               (start-visuals))))
        (when window
          (.setTimeout window
                       (fn []
                         (when (.-speaking synthesis)
                           (start-visuals)))
                       120)))
      (js/Promise.resolve #js {:stop (fn [] (.cancel synthesis))}))

    :else
    (js/Promise.reject (js-error "Web Speech synthesis is not available"))))
