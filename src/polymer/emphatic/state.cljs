(ns polymer.emphatic.state)

(def default-config
  {:enabled true
   :intensity 1
   :priority 42
   :cooldownMs 180})

(def default-state
  {:agency "emphatic"
   :speaking false
   :wordIndex 0
   :currentWord nil
   :plan nil
   :activeSnippets []
   :scheduledCount 0
   :removedCount 0
   :lastEmphaticAt 0
   :lastGesture nil
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
  (let [merged (merge default-config config)]
    {:enabled (boolean (:enabled merged))
     :intensity (clamp 0 2 (number-or (:intensity merged) (:intensity default-config)))
     :priority (int (clamp -1000 1000 (number-or (:priority merged) (:priority default-config))))
     :cooldownMs (clamp 0 5000 (number-or (:cooldownMs merged) (:cooldownMs default-config)))}))

(defn config->state [config]
  (assoc default-state :config (sanitize-config (js-config config))))

(defn configure [state config]
  (update state :config #(sanitize-config (merge % (js-config config)))))

(defn clean-word [word]
  (when word
    (let [value (.trim (str word))]
      (when (pos? (count value)) value))))

(defn record-plan [state plan observed-at]
  (-> state
      (assoc :plan plan
             :lastEvent {:type "emphaticPlanCreated"
                         :text (:text plan)
                         :emphasisCount (count (:emphasisWords plan))
                         :at observed-at})))

(defn record-speech-start [state started-at name text]
  (-> state
      (assoc :speaking true
             :currentWord nil
             :wordIndex 0
             :lastEvent {:type "emphaticSpeechStarted"
                         :name name
                         :text text
                         :at started-at})))

(defn record-word-boundary [state word word-index observed-at]
  (-> state
      (assoc :currentWord word
             :wordIndex (inc (or word-index (:wordIndex state) 0))
             :lastEvent {:type "emphaticWordBoundary"
                         :word word
                         :wordIndex word-index
                         :at observed-at})))

(defn emphatic-cooldown-ready? [state now]
  (>= (- now (:lastEmphaticAt state)) (get-in state [:config :cooldownMs])))

(defn record-schedule [state snippet-name gesture-kind scheduled-at]
  (-> state
      (update :activeSnippets #(vec (distinct (conj % snippet-name))))
      (update :scheduledCount inc)
      (assoc :lastEmphaticAt scheduled-at
             :lastGesture gesture-kind
             :lastEvent {:type "emphaticGestureScheduled"
                         :name snippet-name
                         :gesture gesture-kind
                         :at scheduled-at})))

(defn record-remove [state snippet-name reason removed-at]
  (-> state
      (update :activeSnippets #(vec (remove #{snippet-name} %)))
      (update :removedCount inc)
      (assoc :lastEvent {:type "emphaticGestureRemoved"
                         :name snippet-name
                         :reason reason
                         :at removed-at})))

(defn clear-active [state reason stopped-at]
  (-> state
      (assoc :speaking false
             :currentWord nil
             :wordIndex 0
             :activeSnippets []
             :lastEvent {:type "emphaticStopped"
                         :reason reason
                         :at stopped-at})))

(defn visible-state [state]
  (clj->js state))
