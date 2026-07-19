(ns polymer.tts.planner)

;; The TTS provider planning layer is intentionally small: it produces data plans, not a
;; planner framework. The agency still executes the plan explicitly.
;;
;; In this package "provider plan" means "turn the requested goal plus available world
;; facts into an auditable action list." It does not mean hidden control flow.
;; The browser/backend calls remain in polymer.tts.agency, and pure provider
;; payload cleanup stays beside the provider boundary in the TTS/Azure agencies.

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
  "Build an auditable provider plan-style plan for a speak command."
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

(def active-statuses
  #{"loading" "speaking"})

(defn active-speech?
  "Return true when the current state has provider work that must be stopped
  before replacement/reset."
  [tts-state]
  (contains? active-statuses (:status tts-state)))

(defn command-action [op extra]
  (merge {:op op} extra))

(defn plan-command
  "Plan public TTS dispatch commands into explicit agency actions.

  This is deliberately separate from provider planning above. `plan-speech` and
  `plan-voice-load` decide whether a provider goal can run. `plan-command`
  decides which local session/voice actions must happen before those provider
  goals are attempted."
  [tts-state payload]
  (case (:type payload)
    "configure"
    [(command-action "cancel-voice-loads" {})
     (command-action "configure" {:config (:config payload)})]

    "loadVoices"
    [(command-action "load-voices"
                     {:engine (or (:engine payload)
                                  (get-in tts-state [:config :engine]))})]

    "speak"
    (cond-> []
      (active-speech? tts-state)
      (conj (command-action "stop-active-session" {:reason "replaced"}))

      true
      (conj (command-action "speak" {:command payload})))

    "stop"
    [(command-action "advance-session" {})
     (command-action "stop-session" {:reason "requested"})]

    "reset"
    (cond-> [(command-action "advance-session" {})
             (command-action "cancel-voice-loads" {})]
      (active-speech? tts-state)
      (conj (command-action "stop-active-session" {:reason "reset"}))

      true
      (conj (command-action "reset" {})))

    [(command-action "error"
                     {:message (str "Unknown TTS command: " (:type payload))})]))
