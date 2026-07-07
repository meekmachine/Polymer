(ns polymer.prosodic.state)

;; Prosodic state is a small local ledger. It records speech/prosody facts and
;; active snippet names, but it never stores runtime handles. Runtime-specific
;; handles are owned by Polymer Animation after Prosodic requests a gesture.

(def default-config
  {:enabled true
   :intensity 1
   :priority 30
   :speechGestureEvery 6
   :blinkFastCooldownMs 1200})

(def default-state
  {:agency "prosodic"
   :speaking false
   :wordIndex 0
   :currentWord nil
   :activeSnippets []
   :scheduledCount 0
   :removedCount 0
   :lastGesture nil
   :lastBlinkFastCueAt 0
   :config default-config
   :lastEvent nil})

(defn now-ms []
  (.now js/Date))

(defn finite-number? [value]
  (and (number? value) (js/isFinite value)))

(defn number-or [value fallback]
  (if (finite-number? value) value fallback))

(defn clamp [lo hi value]
  (-> value (max lo) (min hi)))

(defn js-config [config]
  (if config (js->clj config :keywordize-keys true) {}))

(defn sanitize-config [config]
  ;; Clamp all public knobs in one pure function so commands from JS, workers,
  ;; or other agencies share the same agency contract.
  (let [merged (merge default-config config)]
    {:enabled (boolean (:enabled merged))
     :intensity (clamp 0 2 (number-or (:intensity merged) (:intensity default-config)))
     :priority (int (clamp -1000 1000 (number-or (:priority merged) (:priority default-config))))
     :speechGestureEvery (int (clamp 2 32 (number-or (:speechGestureEvery merged)
                                                     (:speechGestureEvery default-config))))
     :blinkFastCooldownMs (clamp 0 10000 (number-or (:blinkFastCooldownMs merged)
                                                    (:blinkFastCooldownMs default-config)))}))

(defn config->state [config]
  (assoc default-state :config (sanitize-config (js-config config))))

(defn configure [state config]
  (update state :config #(sanitize-config (merge % (js-config config)))))

(defn clean-word [word]
  (when word
    (let [value (.trim (str word))]
      (when (pos? (count value)) value))))

(defn record-speech-start [state started-at name]
  (-> state
      (assoc :speaking true
             :currentWord nil
             :wordIndex 0
             :lastEvent {:type "prosodicSpeechStarted"
                         :name name
                         :at started-at})))

(defn record-word-boundary [state word word-index observed-at]
  (-> state
      (assoc :currentWord word
             :wordIndex (inc (or word-index (:wordIndex state) 0))
             :lastEvent {:type "prosodicWordBoundary"
                         :word word
                         :wordIndex word-index
                         :at observed-at})))

(defn record-schedule [state snippet-name gesture-kind scheduled-at]
  (-> state
      (update :activeSnippets #(vec (distinct (conj % snippet-name))))
      (update :scheduledCount inc)
      (assoc :lastGesture gesture-kind
             :lastEvent {:type "prosodicGestureScheduled"
                         :name snippet-name
                         :gesture gesture-kind
                         :at scheduled-at})))

(defn record-remove [state snippet-name reason removed-at]
  (-> state
      (update :activeSnippets #(vec (remove #{snippet-name} %)))
      (update :removedCount inc)
      (assoc :lastEvent {:type "prosodicGestureRemoved"
                         :name snippet-name
                         :reason reason
                         :at removed-at})))

(defn clear-active [state reason stopped-at]
  (-> state
      (assoc :speaking false
             :currentWord nil
             :wordIndex 0
             :activeSnippets []
             :lastEvent {:type "prosodicStopped"
                         :reason reason
                         :at stopped-at})))

(defn blink-fast-cue-ready? [state now]
  (let [cooldown (get-in state [:config :blinkFastCooldownMs])]
    (>= (- now (:lastBlinkFastCueAt state)) cooldown)))

(defn record-blink-fast-cue [state now]
  (assoc state :lastBlinkFastCueAt now))

(defn visible-state [state]
  (clj->js state))
