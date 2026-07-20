(ns polymer.gesture.state
  (:require [clojure.string :as str]))

;; Gesture state is the local ledger for authored arm/hand gesticulations. The
;; saved gesture library can come from LoomLarge, but Polymer keeps only data here:
;; no scene handles, storage, React state, or animation runtime objects.

(def default-config
  {:enabled true
   :intensity 1
   :priority 45
   :defaultDurationMs 700
   :cooldownMs 0
   :rampRatio 0.25
   :holdRatio 0.55
   :returnToBase true
   :replaceActive true
   :maxActive 1})

(def default-state
  {:agency "gesture"
   :gestures {}
   :emojiMappings {}
   :activeSnippets {}
   :lastPlayedByGesture {}
   :scheduledCount 0
   :removedCount 0
   :config default-config
   :lastEvent nil})

(defn now-ms []
  (.now js/Date))

(defn finite-number? [value]
  (and (number? value) (js/isFinite value)))

(defn clamp [lo hi value]
  (-> value (max lo) (min hi)))

(defn number-or [value fallback]
  (if (finite-number? value) value fallback))

(defn js-data [value]
  (cond
    (nil? value) nil
    (or (map? value) (sequential? value)) value
    :else (js->clj value :keywordize-keys true)))

(defn string-key [value]
  (cond
    (nil? value) nil
    (keyword? value) (name value)
    :else (str value)))

(defn clean-string [value]
  (when-let [key (string-key value)]
    (let [trimmed (str/trim key)]
      (when (pos? (count trimmed)) trimmed))))

(defn clean-strings [values]
  (->> (or values [])
       (keep clean-string)
       distinct
       vec))

(defn sanitize-config [config]
  (let [merged (merge default-config (or config {}))]
    {:enabled (not (false? (:enabled merged)))
     :intensity (clamp 0 2 (number-or (:intensity merged) (:intensity default-config)))
     :priority (int (clamp -1000 1000 (number-or (:priority merged) (:priority default-config))))
     :defaultDurationMs (clamp 80 10000 (number-or (:defaultDurationMs merged)
                                                   (:defaultDurationMs default-config)))
     :cooldownMs (clamp 0 10000 (number-or (:cooldownMs merged) (:cooldownMs default-config)))
     :rampRatio (clamp 0.05 0.8 (number-or (:rampRatio merged) (:rampRatio default-config)))
     :holdRatio (clamp 0 0.9 (number-or (:holdRatio merged) (:holdRatio default-config)))
     :returnToBase (not (false? (:returnToBase merged)))
     :replaceActive (not (false? (:replaceActive merged)))
     :maxActive (int (clamp 1 12 (number-or (:maxActive merged) (:maxActive default-config))))}))

(defn normalize-bones [bones]
  (into {}
        (keep (fn [[bone-id transform]]
                (when-let [id (clean-string bone-id)]
                  [id transform])))
        (or bones {})))

(defn normalize-keyframe [keyframe]
  (let [time-ms (number-or (:timeMs keyframe) 0)
        bones (normalize-bones (:bones keyframe))]
    (when (seq bones)
      {:timeMs (max 0 time-ms)
       :bones bones})))

(defn normalize-keyframes [keyframes]
  (->> (or keyframes [])
       (keep normalize-keyframe)
       (sort-by :timeMs)
       vec))

(defn keyframe-bone-names [keyframes]
  (->> keyframes
       (mapcat #(keys (:bones %)))
       (keep clean-string)
       distinct
       vec))

(defn normalize-gesture [fallback-id gesture]
  (let [input (or gesture {})
        id (clean-string (or (:id input) fallback-id))
        bones (normalize-bones (:bones input))
        keyframes (normalize-keyframes (:keyframes input))
        duration-ms (when (contains? input :durationMs)
                      (clamp 80 10000
                             (number-or (:durationMs input)
                                        (:defaultDurationMs default-config))))
        priority (when (contains? input :priority)
                   (int (clamp -1000 1000
                               (number-or (:priority input)
                                          (:priority default-config)))))
        affected-bones (or (seq (clean-strings (:affectedBones input)))
                           (seq (keys bones))
                           (seq (keyframe-bone-names keyframes)))]
    (when id
      (-> input
          (assoc :id id
                 :name (or (clean-string (:name input)) id)
                 :affectedBones (vec affected-bones)
                 :affectedAUs (clean-strings (:affectedAUs input))
                 :bones bones)
          (cond->
           duration-ms (assoc :durationMs duration-ms)
           priority (assoc :priority priority)
           (seq keyframes) (assoc :keyframes keyframes)
           (clean-string (:description input)) (assoc :description (clean-string (:description input)))
           (clean-string (:captureSource input)) (assoc :captureSource (clean-string (:captureSource input)))
           (clean-string (:sourceText input)) (assoc :sourceText (clean-string (:sourceText input)))
           (clean-string (:textRepresentation input)) (assoc :textRepresentation (clean-string (:textRepresentation input)))
           (clean-string (:emoji input)) (assoc :emoji (clean-string (:emoji input)))
           (seq (:tags input)) (assoc :tags (clean-strings (:tags input))))))))

(defn normalize-gesture-library [gestures]
  (into {}
        (keep (fn [[gesture-id gesture]]
                (when-let [normalized (normalize-gesture gesture-id gesture)]
                  [(:id normalized) normalized])))
        (or gestures {})))

(defn gesture-emoji-mappings [gestures]
  (into {}
        (keep (fn [[gesture-id gesture]]
                (when-let [emoji (clean-string (:emoji gesture))]
                  [emoji gesture-id])))
        gestures))

(defn normalize-emoji-mappings [emoji-mappings gestures]
  (merge
   (gesture-emoji-mappings gestures)
   (into {}
         (keep (fn [[emoji gesture-id]]
                 (when-let [clean-emoji (clean-string emoji)]
                   (when-let [clean-id (clean-string gesture-id)]
                     [clean-emoji clean-id]))))
         (or emoji-mappings {}))))

(defn library-from-config [config]
  (normalize-gesture-library (or (:gestures config)
                                 (:characterGestures config)
                                 (:gestureLibrary config))))

(defn emoji-mappings-from-config [config gestures]
  (normalize-emoji-mappings (or (:emojiMappings config)
                                (:gestureEmojiMappings config))
                            gestures))

(defn config->state [config]
  (let [data (or (js-data config) {})
        gestures (library-from-config data)]
    (assoc default-state
           :gestures gestures
           :emojiMappings (emoji-mappings-from-config data gestures)
           :config (sanitize-config data))))

(defn configure [state config]
  (let [data (or (js-data config) {})
        configured (update state :config #(sanitize-config (merge % data)))]
    (if (or (contains? data :gestures)
            (contains? data :characterGestures)
            (contains? data :gestureLibrary)
            (contains? data :emojiMappings)
            (contains? data :gestureEmojiMappings))
      (let [gestures (if (or (contains? data :gestures)
                             (contains? data :characterGestures)
                             (contains? data :gestureLibrary))
                       (library-from-config data)
                       (:gestures configured))
            mapping-input (if (or (contains? data :gestures)
                                  (contains? data :characterGestures)
                                  (contains? data :gestureLibrary))
                            (or (:emojiMappings data) (:gestureEmojiMappings data))
                            (or (:emojiMappings data)
                                (:gestureEmojiMappings data)
                                (:emojiMappings configured)))]
        (assoc configured
               :gestures gestures
               :emojiMappings (normalize-emoji-mappings mapping-input gestures)))
      configured)))

(defn load-gestures [state gestures emoji-mappings loaded-at]
  (let [library (normalize-gesture-library (or (js-data gestures) {}))]
    (-> state
        (assoc :gestures library
               :emojiMappings (normalize-emoji-mappings (js-data emoji-mappings) library))
        (assoc :lastEvent {:type "gestureLibraryUpdated"
                           :gestureCount (count library)
                           :emojiCount (count (normalize-emoji-mappings (js-data emoji-mappings) library))
                           :at loaded-at}))))

(defn gesture-ready? [state gesture-id now]
  (let [cooldown-ms (get-in state [:config :cooldownMs])
        last-played (get-in state [:lastPlayedByGesture gesture-id] 0)]
    (or (zero? cooldown-ms)
        (>= (- now last-played) cooldown-ms))))

(defn active-names [state]
  (vec (keys (:activeSnippets state))))

(defn active-names-for-gesture [state gesture-id]
  (->> (:activeSnippets state)
       (keep (fn [[name active]]
               (when (= gesture-id (:gestureId active)) name)))
       vec))

(defn record-schedule [state gesture snippet scheduled-at]
  (let [name (:name snippet)
        gesture-id (:id gesture)]
    (-> state
        (assoc-in [:activeSnippets name]
                  {:name name
                   :gestureId gesture-id
                   :gestureName (:name gesture)
                   :emoji (:emoji gesture)
                   :scope (:scope gesture)
                   :affectedBones (:affectedBones gesture)
                   :maxTime (:maxTime snippet)
                   :loop (:loop snippet)
                   :priority (:snippetPriority snippet)
                   :trigger (get-in snippet [:metadata :trigger])
                   :intent (get-in snippet [:metadata :intent])
                   :scheduledAt scheduled-at})
        (assoc-in [:lastPlayedByGesture gesture-id] scheduled-at)
        (update :scheduledCount inc)
        (assoc :lastEvent {:type "gestureScheduled"
                           :name name
                           :gestureId gesture-id
                           :at scheduled-at}))))

(defn record-remove [state name reason removed-at]
  (-> state
      (update :activeSnippets dissoc name)
      (update :removedCount inc)
      (assoc :lastEvent {:type "gestureRemoved"
                         :name name
                         :reason reason
                         :at removed-at})))

(defn visible-state-map [state]
  (assoc state
         :gestureCount (count (:gestures state))
         :emojiCount (count (:emojiMappings state))
         :gestures (vec (keys (:gestures state)))))

(defn visible-state [state]
  (clj->js (visible-state-map state)))
