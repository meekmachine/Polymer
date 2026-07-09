(ns polymer.tts.state)

;; The TTS agency state is intentionally separate from LipSync state.
;; TTS owns provider/session facts; LipSync owns mouth planning.

(defn finite-number?
  "Return true only for real numeric values that can safely enter timing math."
  [value]
  (and (number? value)
       (not (js/isNaN value))
       (js/isFinite value)))

(defn clamp
  "Clamp a number into an inclusive range, using fallback when input is absent."
  [minimum maximum value fallback]
  (let [candidate (if (finite-number? value) value fallback)]
    (min maximum (max minimum candidate))))

(defn string-or
  "Keep string configuration values stable and avoid leaking nil into JS events."
  [value fallback]
  (if (string? value) value fallback))

(defn bool-or
  "Normalize optional booleans without treating false as missing."
  [value fallback]
  (if (nil? value) fallback (boolean value)))

(def default-config
  ;; Defaults mirror the old Latticework TTS defaults and the current UI defaults.
  {:engine "webSpeech"
   :backendUrl ""
   :voiceName ""
   :azureVoiceName "en-US-JennyNeural"
   :azureStyle ""
   :azureStyleDegree nil
   :rate 1
   :pitch 1
   :volume 1
   :visualLeadMs 35
   :lipsyncIntensity 1
   :jawScale 1
   :tongueScale 1
   :webSpeechDriftThresholdSec 0.08
   :azureDriftThresholdSec 0.04
   :azureCacheLimit 8
   :debug false
   :providers nil})

(defn normalize-config
  "Create the public, clamped TTS config map used by all agency decisions."
  [config]
  (let [input (merge default-config (or config {}))]
    {:engine (string-or (:engine input) (:engine default-config))
     :backendUrl (string-or (:backendUrl input) (:backendUrl default-config))
     :voiceName (string-or (:voiceName input) (:voiceName default-config))
     :azureVoiceName (string-or (:azureVoiceName input) (:azureVoiceName default-config))
     :azureStyle (string-or (:azureStyle input) (:azureStyle default-config))
     :azureStyleDegree (:azureStyleDegree input)
     :rate (clamp 0.5 2.0 (:rate input) (:rate default-config))
     :pitch (clamp 0.5 2.0 (:pitch input) (:pitch default-config))
     :volume (clamp 0 1 (:volume input) (:volume default-config))
     :visualLeadMs (clamp 0 250 (:visualLeadMs input) (:visualLeadMs default-config))
     :lipsyncIntensity (clamp 0 2 (:lipsyncIntensity input) (:lipsyncIntensity default-config))
     :jawScale (clamp 0 2 (:jawScale input) (:jawScale default-config))
     :tongueScale (clamp 0 2 (:tongueScale input) (:tongueScale default-config))
     :webSpeechDriftThresholdSec (clamp 0 1 (:webSpeechDriftThresholdSec input) (:webSpeechDriftThresholdSec default-config))
     :azureDriftThresholdSec (clamp 0 1 (:azureDriftThresholdSec input) (:azureDriftThresholdSec default-config))
     :azureCacheLimit (int (clamp 0 64 (:azureCacheLimit input) (:azureCacheLimit default-config)))
     :debug (bool-or (:debug input) false)
     :providers (:providers input)}))

(defn default-state
  "Build a fresh state ledger for one TTS agency instance."
  [config]
  (let [normalized-config (normalize-config config)]
    {:agency "tts"
     :status "idle"
     :engine (:engine normalized-config)
     :speaking false
     :currentText nil
     :snippetName nil
     :sessionId 0
     :startedAt nil
     :endedAt nil
     :wordIndex 0
     :webSpeechVoices []
     :azureVoices []
     :azureStatus "checking"
     :azureStatusMessage "Azure voices have not been checked yet."
     :lastPlan nil
     :lastError nil
     :config normalized-config}))

(defn visible-state
  "Expose serializable state without private JS handles or provider functions."
  [state]
  (update-in state [:config] dissoc :providers))

(defn configure
  "Merge public config into state and keep status/session fields untouched."
  [state config]
  (let [next-config (normalize-config (merge (:config state) (or config {})))]
    (assoc state
           :engine (:engine next-config)
           :config next-config)))

(defn next-session
  "Advance the session token so late provider callbacks can be ignored."
  [state]
  (update state :sessionId inc))

(defn record-plan
  "Store provider-plan data that explains how this speech command will execute."
  [state plan]
  (assoc state :lastPlan plan))

(defn record-loading
  "Mark speech synthesis/playback setup as active."
  [state engine text snippet-name]
  (assoc state
         :status "loading"
         :engine engine
         :currentText text
         :snippetName snippet-name
         :speaking false
         :startedAt nil
         :endedAt nil
         :wordIndex 0
         :lastError nil))

(defn record-speaking
  "Mark real audio playback as started."
  [state started-at]
  (assoc state
         :status "speaking"
         :speaking true
         :startedAt started-at
         :endedAt nil
         :lastError nil))

(defn record-word-boundary
  "Remember the latest provider word boundary without making host code poll state."
  [state word-index]
  (assoc state :wordIndex (inc (max 0 (int word-index)))))

(defn record-stopped
  "Return the agency to idle after stop, end, or replacement."
  [state ended-at]
  (assoc state
         :status "idle"
         :speaking false
         :currentText nil
         :snippetName nil
         :endedAt ended-at))

(defn record-error
  "Move the agency into an error state while keeping the last useful context."
  [state message]
  (assoc state
         :status "error"
         :speaking false
         :lastError message))

(defn record-web-speech-voices
  "Store normalized browser voice choices for UI consumers."
  [state voices]
  (assoc state :webSpeechVoices (vec voices)))

(defn record-azure-status
  "Store backend Azure availability separately from the active speech status."
  [state status message voices]
  (cond-> (assoc state
                 :azureStatus status
                 :azureStatusMessage message)
    voices (assoc :azureVoices (vec voices))))
