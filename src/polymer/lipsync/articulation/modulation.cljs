(ns polymer.lipsync.articulation.modulation
  (:require [clojure.string :as str]
            [polymer.lipsync.state :as state]))

;; Provider-neutral JA/LI modulation for JALI-style articulation.
;; Polymer consumes optional stress, speech-style, and audio-feature facts.
;; It does not capture audio, call Azure, or touch browser APIs here.

(def speech-style-gains
  ;; Approximate the JALI paper's jaw/lip power bands as relative gains on top
  ;; of the deterministic planner targets. "conversational" is identity.
  {"mumbled" {:jaw 0.28 :lip 0.30 :articulation 0.40}
   "relaxed" {:jaw 0.55 :lip 0.58 :articulation 0.65}
   "conversational" {:jaw 1.0 :lip 1.0 :articulation 1.0}
   "emphasized" {:jaw 1.35 :lip 1.45 :articulation 1.40}
   "shouted" {:jaw 1.55 :lip 1.50 :articulation 1.55}})

(defn normalize-speech-style [value]
  (let [normalized (some-> value str str/lower-case str/trim)]
    (if (contains? speech-style-gains normalized)
      normalized
      "conversational")))

(defn style-gains [config]
  (get speech-style-gains
       (normalize-speech-style (:speechStyle config))
       (get speech-style-gains "conversational")))

(defn finite-feature [event & keys]
  (some (fn [key]
          (let [value (get event key)]
            (when (state/finite-number? value) value)))
        keys))

(defn clamp-feature
  ([value] (clamp-feature value 0 2 1))
  ([value lo hi fallback]
   (state/clamp lo hi (state/number-or value fallback))))

(defn event-stress [event]
  ;; Stress/prominence are optional provider/host facts. Missing data must fall
  ;; back to 1.0 so text and Azure timelines keep deterministic behavior.
  (clamp-feature
   (finite-feature event
                   :stress
                   :wordProminence
                   :prominence
                   :word_prominence)
   0.55 1.55 1))

(defn event-energy [event]
  ;; Prefer explicit audio-energy fields. Do not read bare :intensity here —
  ;; that name collides with LipSyncConfig intensity in host payloads.
  (clamp-feature
   (finite-feature event
                   :energy
                   :audioEnergy
                   :audio_energy
                   :audioIntensity)
   0.35 1.8 1))

(defn event-high-freq-energy [event]
  (clamp-feature
   (finite-feature event
                   :highFreqEnergy
                   :hfEnergy
                   :high_freq_energy
                   :highFrequencyEnergy)
   0.35 1.8 1))

(defn event-pitch-gain [event]
  ;; Pitch is a soft emphasis cue, not a second jaw planner. Keep the gain small
  ;; so missing pitch data and flat contours stay neutral.
  (let [pitch (finite-feature event :pitch :f0 :fundamentalFreq)]
    (if-not pitch
      1
      (clamp-feature (+ 1 (* (- (state/clamp 60 400 pitch) 160) 0.0015))
                     0.85 1.2 1))))

(defn event-emotion-gain
  ([event] (event-emotion-gain event nil))
  ([event config]
   (let [from-event (finite-feature event :emotionIntensity :emotion_intensity)
         from-config (when config (:emotionIntensity config))]
     (clamp-feature (or from-event from-config) 0.5 1.6 1))))

(defn class-name [value]
  (cond
    (keyword? value) (name value)
    (string? value) (str/lower-case value)
    (nil? value) nil
    :else (str/lower-case (str value))))

(defn event-classes [event]
  (into #{}
        (keep class-name)
        (concat (or (:phonemeClasses event) [])
                [(:phonemeClass event)])))

(defn has-class? [event class]
  (contains? (event-classes event) class))

(defn fricative-or-plosive? [event]
  (or (has-class? event "fricative")
      (has-class? event "sibilant")
      (has-class? event "labiodental")
      (has-class? event "dental")
      (has-class? event "obstruent")
      (has-class? event "bilabial")))

(defn lip-audio-gain [event]
  ;; JALI scales lip power for fricatives/plosives from high-frequency energy
  ;; when available, otherwise from broadband energy.
  (if (fricative-or-plosive? event)
    (let [hf (finite-feature event
                             :highFreqEnergy
                             :hfEnergy
                             :high_freq_energy
                             :highFrequencyEnergy)
          energy (finite-feature event
                                 :energy
                                 :audioEnergy
                                 :audio_energy
                                 :audioIntensity)]
      (clamp-feature (or hf energy) 0.35 1.8 1))
    (event-energy event)))

(defn jaw-audio-gain [event]
  ;; Vowels/open sounds get jaw power from broadband energy. Narrow consonants
  ;; stay near 1 unless an explicit energy feature is present.
  (if (or (has-class? event "vowel")
          (has-class? event "diphthong"))
    (* (event-energy event) (event-pitch-gain event))
    (clamp-feature (finite-feature event
                                   :energy
                                   :audioEnergy
                                   :audio_energy
                                   :audioIntensity)
                   0.5 1.4 1)))

(defn planning-scales [config]
  (let [style (style-gains config)
        articulation (state/clamp 0 2
                                  (state/number-or (:articulationScale config) 1))
        emotion (clamp-feature (:emotionIntensity config) 0.5 1.6 1)]
    {:lip (* (state/clamp 0 2 (state/number-or (:intensity config) 1))
             (state/clamp 0 2 (state/number-or (:lipScale config) 1))
             articulation
             (:lip style)
             (:articulation style)
             emotion)
     :jaw (* (state/clamp 0 2 (state/number-or (:jawScale config) 1))
             (:jaw style)
             emotion)
     :tongue (* (state/clamp 0 2 (state/number-or (:tongueScale config) 1))
                emotion)}))

(defn event-lip-intensity [event config]
  ;; Config/style/emotion live in planning-scales. Event facts only multiply
  ;; stress and optional audio features on top.
  (* (:lip (planning-scales config))
     (event-stress event)
     (lip-audio-gain event)
     (event-emotion-gain event)))

(defn event-jaw-activation [event _config]
  ;; Style/emotion jaw gains are applied via planning-scales -> jawScale so the
  ;; jaw planner does not double-scale them.
  (* (state/clamp 0 2
                  (state/number-or (:jawActivation event) 0))
     (event-stress event)
     (jaw-audio-gain event)
     (event-emotion-gain event)))

(defn enrich-event [event config]
  ;; Attach planner-ready amplitudes without mutating provider identity. The
  ;; original feature fields remain on the event for diagnostics/tests.
  (-> event
      (assoc :plannedLipIntensity (event-lip-intensity event config))
      (assoc :plannedJawActivation (event-jaw-activation event config))))

(defn enrich-events [events config]
  (mapv #(enrich-event % config) (or events [])))
