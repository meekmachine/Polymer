(ns polymer.lipsync.state
  (:require [clojure.string :as str]))

;; LipSync state is the agency-local ledger for one character's speech
;; animation timeline. Provider credentials, audio playback, HTTP, LiveKit, and
;; browser APIs stay outside Polymer; this state only tracks the deterministic
;; timing data needed to schedule and correct viseme animation.

(def default-config
  {:intensity 1
   :speechRate 1
   :jawScale 1
   :tongueScale 1
   :rampMs 15
   :holdMs 40
   :priority 50
   :visualLeadMs 0
   :wordDriftThresholdSec 0.06})

(def default-state
  {:agency "lipSync"
   :speaking false
   :currentWord nil
   :currentViseme nil
   :snippetName nil
   :source nil
   :text nil
   :startTime nil
   :audioStartedAt nil
   :audioTimeSec nil
   :maxTime 0
   :wordIndex 0
   :wordTimings []
   :scheduledCount 0
   :stoppedCount 0
   :syncCorrectionCount 0
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

(defn sanitize-config [config]
  ;; This is the only place public config gets clamped. The rest of the agency
  ;; can then treat config as ordinary data, whether it came from LoomLarge UI,
  ;; another Polymer agency, or a worker message.
  (let [merged (merge default-config config)]
    {:intensity (clamp 0 2 (number-or (:intensity merged) (:intensity default-config)))
     :speechRate (clamp 0.2 3 (number-or (:speechRate merged) (:speechRate default-config)))
     :jawScale (clamp 0 2 (number-or (:jawScale merged) (:jawScale default-config)))
     :tongueScale (clamp 0 2 (number-or (:tongueScale merged) (:tongueScale default-config)))
     :rampMs (clamp 0 200 (number-or (:rampMs merged) (:rampMs default-config)))
     :holdMs (clamp 0 500 (number-or (:holdMs merged) (:holdMs default-config)))
     :priority (int (clamp -1000 1000 (number-or (:priority merged) (:priority default-config))))
     :visualLeadMs (clamp 0 250 (number-or (:visualLeadMs merged) (:visualLeadMs default-config)))
     :wordDriftThresholdSec (clamp 0.01 0.5 (number-or (:wordDriftThresholdSec merged)
                                                       (:wordDriftThresholdSec default-config)))}))

(defn js-config [config]
  (if config (js->clj config :keywordize-keys true) {}))

(defn config->state [config]
  (assoc default-state :config (sanitize-config (js-config config))))

(defn configure [state config]
  (update state :config #(sanitize-config (merge % (js-config config)))))

(defn clean-word [word]
  (when word
    (let [value (str/trim (str word))]
      (when (pos? (count value)) value))))

(defn word-start-sec [timing]
  (number-or (or (:startSec timing)
                 (:start timing)
                 (:start_time timing))
             0))

(defn word-end-sec [timing]
  (number-or (or (:endSec timing)
                 (:end timing)
                 (:end_time timing))
             (word-start-sec timing)))

(defn normalize-word-timing [timing]
  ;; Provider word-boundary messages are not perfectly standardized. Normalize
  ;; both the LiveKit-style start/end shape and the Azure-style start_time/end_time
  ;; shape into the one state shape used for drift correction.
  (let [word (clean-word (:word timing))
        start-sec (max 0 (word-start-sec timing))
        end-sec (max start-sec (word-end-sec timing))]
    (when word
      {:word word
       :startSec start-sec
       :endSec end-sec})))

(defn normalize-word-timings [word-timings]
  (->> (or word-timings [])
       (map normalize-word-timing)
       (remove nil?)
       vec))

(defn record-start [state timeline snippet-name started-at max-time]
  ;; A timeline starts as one utterance-level animation snippet. Word timings are
  ;; retained only for drift correction; they are not used to create per-word
  ;; snippets that can pile up or fight each other.
  (let [visemes (:visemes timeline)
        first-viseme (:visemeId (first visemes))]
    (-> state
        (assoc :speaking true
               :currentWord nil
               :currentViseme first-viseme
               :snippetName snippet-name
               :source (or (:source timeline) "unknown")
               :text (:text timeline)
               :startTime started-at
               :audioStartedAt nil
               :audioTimeSec nil
               :maxTime max-time
               :wordIndex 0
               :wordTimings (normalize-word-timings (:wordTimings timeline)))
        (update :scheduledCount inc)
        (assoc :lastEvent {:type "lipSyncTimelineStarted"
                           :name snippet-name
                           :source (or (:source timeline) "unknown")
                           :at started-at}))))

(defn record-stop [state stopped-at reason]
  (-> state
      (assoc :speaking false
             :currentWord nil
             :currentViseme nil
             :snippetName nil
             :source nil
             :text nil
             :startTime nil
             :audioStartedAt nil
             :audioTimeSec nil
             :maxTime 0
             :wordIndex 0
             :wordTimings [])
      (update :stoppedCount inc)
      (assoc :lastEvent {:type "lipSyncTimelineStopped"
                         :reason reason
                         :at stopped-at})))

(defn record-word-boundary [state word word-index observed-at]
  (-> state
      (assoc :currentWord word
             :wordIndex (inc (or word-index (:wordIndex state) 0)))
      (assoc :lastEvent {:type "lipSyncWordBoundary"
                         :word word
                         :wordIndex word-index
                         :at observed-at})))

(defn record-word-timings [state word-timings updated-at]
  ;; Provider timing metadata can arrive after viseme data. Updating it in the
  ;; agency keeps later word-boundary drift correction accurate without asking
  ;; LoomLarge to manage any LipSync internals.
  (-> state
      (assoc :wordTimings (normalize-word-timings word-timings)
             :wordIndex 0)
      (assoc :lastEvent {:type "lipSyncWordTimingsUpdated"
                         :count (count (normalize-word-timings word-timings))
                         :at updated-at})))

(defn record-sync-correction [state name offset-sec corrected-at]
  (-> state
      (update :syncCorrectionCount inc)
      (assoc :lastEvent {:type "lipSyncSyncCorrection"
                         :name name
                         :offsetSec offset-sec
                         :at corrected-at})))

(defn record-audio-started [state audio-time-sec observed-at]
  ;; Audio remains a host side effect; LipSync only records the host clock value
  ;; that should drive the already-scheduled animation timeline.
  (-> state
      (assoc :audioStartedAt observed-at
             :audioTimeSec (max 0 (number-or audio-time-sec 0)))
      (assoc :lastEvent {:type "lipSyncAudioStarted"
                         :audioTimeSec (max 0 (number-or audio-time-sec 0))
                         :at observed-at})))

(defn record-audio-time [state audio-time-sec observed-at]
  (-> state
      (assoc :audioTimeSec (max 0 (number-or audio-time-sec 0)))
      (assoc :lastEvent {:type "lipSyncAudioTime"
                         :audioTimeSec (max 0 (number-or audio-time-sec 0))
                         :at observed-at})))

(defn visible-state [state]
  (clj->js state))
