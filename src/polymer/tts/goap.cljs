(ns polymer.tts.goap)

;; The TTS GOAP layer is intentionally small: it produces data plans, not a
;; planner framework. The agency still executes the plan explicitly.

(defn provider-ready?
  "A provider is ready when the required browser/backend capability exists."
  [world engine]
  (case engine
    "azure" (boolean (:backendUrl world))
    "webSpeech" (boolean (:hasWebSpeech world))
    "livekit" false
    false))

(defn provider-goal
  "Turn a speak command into the provider goal the agency must satisfy."
  [command config world]
  (let [engine (or (:engine command) (:engine config) "webSpeech")]
    {:type "speak"
     :engine engine
     :text (:text command)
     :providerReady (provider-ready? world engine)
     :hasText (pos? (count (or (:text command) "")))}))

(defn speech-steps
  "Return the ordered action data for a speech goal."
  [goal]
  (cond
    (not (:hasText goal))
    [{:op "fail" :reason "missing-text"}]

    (not (:providerReady goal))
    [{:op "fail" :reason "provider-not-ready" :engine (:engine goal)}]

    (= "azure" (:engine goal))
    [{:op "configure-lipsync"}
     {:op "synthesize-azure"}
     {:op "play-azure-audio"}
     {:op "emit-word-boundaries"}]

    (= "webSpeech" (:engine goal))
    [{:op "configure-lipsync"}
     {:op "speak-web-speech"}
     {:op "use-web-speech-boundaries"}]

    :else
    [{:op "fail" :reason "unsupported-engine" :engine (:engine goal)}]))

(defn plan-speech
  "Build an auditable GOAP-style plan for a speak command."
  [command config world]
  (let [goal (provider-goal command config world)
        steps (speech-steps goal)]
    {:goal goal
     :steps steps
     :ok (not= "fail" (:op (first steps)))}))

(defn plan-voice-load
  "Build a small plan for loading provider voice choices."
  [engine world]
  {:goal {:type "loadVoices"
          :engine engine
          :providerReady (provider-ready? world engine)}
   :steps (case engine
            "azure" [{:op "load-azure-voices"}]
            "webSpeech" [{:op "load-web-speech-voices"}]
            [{:op "fail" :reason "unsupported-engine" :engine engine}])
   :ok (contains? #{"azure" "webSpeech"} engine)})
