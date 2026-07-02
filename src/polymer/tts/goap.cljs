(ns polymer.tts.goap)

;; The TTS GOAP layer is intentionally small: it produces data plans, not a
;; planner framework. The agency still executes the plan explicitly.
;;
;; In this package "GOAP" means "turn the requested goal plus available world
;; facts into an auditable action list." It does not mean hidden control flow.
;; The browser/backend calls remain in polymer.tts.agency, and the pure provider
;; payload cleanup remains in polymer.tts.transducers.

(def supported-engines
  ;; Keep supported provider names in one set so speech and voice-load planning
  ;; reject unknown strings the same way.
  #{"azure" "webSpeech"})

(defn present-string?
  "Treat only non-empty strings as configured provider values."
  [value]
  (and (string? value) (pos? (count value))))

(defn provider-ready?
  "A provider is ready when the required browser/backend capability exists."
  [world engine]
  (case engine
    "azure" (boolean (or (present-string? (:backendUrl world))
                         (:hasAzureSynthesize world)
                         (:hasAzureVoices world)))
    "webSpeech" (boolean (:hasWebSpeech world))
    "livekit" false
    false))

(defn failure-step
  "Return the first reason a goal cannot run, or nil when it is executable."
  [goal]
  (cond
    (not (contains? supported-engines (:engine goal)))
    {:op "fail" :reason "unsupported-engine" :engine (:engine goal)}

    (false? (:hasText goal))
    {:op "fail" :reason "missing-text"}

    (not (:providerReady goal))
    {:op "fail" :reason "provider-not-ready" :engine (:engine goal)}

    :else nil))

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
    (failure-step goal)
    [(failure-step goal)]

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

(defn plan-ok?
  "A plan is executable when the first step is not an explicit failure."
  [steps]
  (not= "fail" (:op (first steps))))

(defn plan-speech
  "Build an auditable GOAP-style plan for a speak command."
  [command config world]
  (let [goal (provider-goal command config world)
        steps (speech-steps goal)]
    {:goal goal
     :steps steps
     :ok (plan-ok? steps)}))

(defn voice-load-goal
  "Turn a load-voices command into a provider availability goal."
  [engine world]
  {:type "loadVoices"
   :engine engine
   :providerReady (provider-ready? world engine)})

(defn voice-load-steps
  "Return the ordered action data for loading provider voice choices."
  [goal]
  (cond
    (failure-step goal)
    [(failure-step goal)]

    (= "azure" (:engine goal))
    [{:op "load-azure-voices"}]

    (= "webSpeech" (:engine goal))
    [{:op "load-web-speech-voices"}]

    :else
    [{:op "fail" :reason "unsupported-engine" :engine (:engine goal)}]))

(defn plan-voice-load
  "Build a small plan for loading provider voice choices."
  [engine world]
  (let [goal (voice-load-goal engine world)
        steps (voice-load-steps goal)]
    {:goal goal
     :steps steps
     :ok (plan-ok? steps)}))
