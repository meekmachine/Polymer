(ns polymer.lipsync.articulation.snippet
  (:require [clojure.string :as str]
            [polymer.lipsync.articulation.jaw :as jaw]
            [polymer.lipsync.articulation.lip :as lip]
            [polymer.lipsync.articulation.modulation :as modulation]
            [polymer.lipsync.state :as state]
            [polymer.lipsync.articulation.tongue :as tongue]
            [polymer.lipsync.articulation.visemes :as visemes]))

;; Snippet construction is pure composition: normalize events, apply JALI
;; modulation facts, then ask the lip/jaw/tongue planners for independent
;; curves. Host UI, audio playback, LiveKit, Azure credentials, and engine
;; handles stay outside this namespace.

(def jaw-au "26")
(def intensity-eps lip/intensity-eps)

(defn finite-number? [value]
  (and (number? value) (js/isFinite value)))

(defn feature-keys-present [event]
  (select-keys event
               [:stress :wordProminence :prominence :word_prominence
                :energy :audioEnergy :audio_energy :audioIntensity
                :highFreqEnergy :hfEnergy :high_freq_energy :highFrequencyEnergy
                :pitch :f0 :fundamentalFreq
                :emotionIntensity :emotion_intensity
                :confidence]))

(defn normalize-event [event]
  (let [viseme-id (or (:visemeId event) (:viseme_id event) (:id event))
        offset-ms (or (:offsetMs event) (:offset_ms event) (:offset event) 0)
        duration-ms (or (:durationMs event) (:duration_ms event) (:duration event) 0)
        jaw-activation (or (:jawActivation event)
                           (:jaw_activation event)
                           (:jaw event)
                           (:jawAmount event))
        features (feature-keys-present event)]
    (when (and (finite-number? viseme-id)
               (finite-number? offset-ms)
               (finite-number? duration-ms)
               (pos? duration-ms))
      (let [canonical-id (int (state/clamp 0 14 viseme-id))]
        (cond->
         (merge
          {:visemeId canonical-id
           :offsetMs (max 0 offset-ms)
           :durationMs (max 1 duration-ms)
           :jawActivation (if (finite-number? jaw-activation)
                            (state/clamp 0 2 jaw-activation)
                            (visemes/jaw-activation-for-viseme canonical-id))}
          features)
          (:phoneme event) (assoc :phoneme (:phoneme event))
          (or (:phonemeClass event) (:phoneme_class event))
          (assoc :phonemeClass (or (:phonemeClass event) (:phoneme_class event)))
          (or (:phonemeClasses event) (:phoneme_classes event))
          (assoc :phonemeClasses (or (:phonemeClasses event) (:phoneme_classes event))))))))

(defn normalize-events [events]
  (->> (into [] (keep normalize-event) (or events []))
       (sort-by :offsetMs)
       vec))

(defn sample-curve-at [curve time]
  (lip/sample-curve-at curve time))

(defn build-jaw-curve [events jaw-scale]
  (jaw/build-jaw-curve events jaw-scale))

(def snippet-counter (atom 0))

(defn next-snippet-name [prefix]
  (str prefix "_" (.now js/Date) "_" (swap! snippet-counter inc)))

(defn text-snippet-name [text]
  (let [words (->> (str/split (or text "") #"\s+")
                   (take 3)
                   (str/join "_")
                   str/lower-case
                   (#(str/replace % #"[^a-z_]" "")))]
    (next-snippet-name (str "lipSync_" (if (pos? (count words)) words "timeline")))))

(defn events-for-jaw [events config]
  (mapv (fn [event]
          (assoc event
                 :jawActivation
                 (state/number-or (:plannedJawActivation event)
                                  (modulation/event-jaw-activation event config))))
        events))

(defn build-curves [events config]
  (let [enriched (modulation/enrich-events events config)
        scales (modulation/planning-scales config)
        lip-curves (lip/build-lip-curves enriched config)
        jaw-curve (build-jaw-curve (events-for-jaw enriched config) (:jaw scales))
        tongue-curves (tongue/build-tongue-curves enriched (:tongue scales))
        with-jaw (if (empty? jaw-curve)
                   lip-curves
                   (assoc lip-curves jaw-au jaw-curve))]
    (merge with-jaw tongue-curves)))

(defn lipsync-channel-target [curve-key]
  (let [key (str curve-key)
        numeric-id (when (re-matches #"^\d+$" key)
                     (js/parseInt key 10))]
    (cond
      (= key jaw-au)
      {:type "bone" :id "JAW" :channel "rz" :maxDegrees 30}

      (and (some? numeric-id) (<= 0 numeric-id 14))
      {:type "viseme" :id numeric-id}

      (some? numeric-id)
      {:type "au" :id numeric-id}

      :else
      {:type "morph" :id key})))

(defn curves->channels [curves]
  (into []
        (map (fn [[curve-key curve]]
               {:target (lipsync-channel-target curve-key)
                :keyframes curve}))
        curves))

(defn max-snippet-time [events curves]
  (let [event-max (transduce (map #(/ (+ (:offsetMs %) (:durationMs %)) 1000))
                             max
                             0
                             events)
        curve-max (transduce (mapcat (fn [[_ curve]] (map :time curve)))
                             max
                             0
                             curves)]
    (max event-max curve-max)))

(defn build-lipsync-snippet
  ([events config] (build-lipsync-snippet events config nil))
  ([events config name]
   (let [config (state/sanitize-config config)
         normalized-events (normalize-events events)
         snippet-name (or name (next-snippet-name "lipSync"))
         curves (build-curves normalized-events config)
         max-time (max-snippet-time normalized-events curves)
         scales (modulation/planning-scales config)]
     {:name snippet-name
      :snippetPriority (:priority config)
      :snippetPlaybackRate 1
      :snippetIntensityScale 1
      :snippetJawScale (:jaw scales)
      :snippetLipScale (:lip scales)
      :autoVisemeJaw false
      :loop false
      :maxTime max-time
      :curves curves
      :channels (curves->channels curves)
      :metadata {:agency "lipSync"
                 :visemeCount (count normalized-events)
                 :speechStyle (:speechStyle config)
                 :tongueCurveCount (count (select-keys curves tongue/tongue-au-keys))}})))

(defn build-text-snippet [text events config]
  (build-lipsync-snippet events config (text-snippet-name text)))
