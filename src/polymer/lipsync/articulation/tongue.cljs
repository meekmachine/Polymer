(ns polymer.lipsync.articulation.tongue
  (:require [clojure.string :as str]
            [polymer.lipsync.state :as state]
            [polymer.lipsync.articulation.visemes :as visemes]))

;; Tongue planning is an independent LipSync articulator beside lips and jaw.
;; The input is the same normalized phoneme/viseme timeline as the rest of
;; LipSync. The output is still ordinary typed AU channels, so Embody receives
;; the same snippet data shape as lips, jaw, blink, and future agencies.

(def tongue-up-au "37")
(def tongue-down-au "38")
(def tongue-left-au "39")
(def tongue-right-au "40")
(def tongue-tilt-left-au "41")
(def tongue-tilt-right-au "42")
(def tongue-narrow-au "73")
(def tongue-wide-au "74")
(def tongue-tip-up-au "76")
(def tongue-tip-down-au "77")

(def tongue-au-keys
  ;; Keep the public list near the constants so snippet metadata and tests can
  ;; count tongue channels without hard-coding the planner internals.
  [tongue-up-au
   tongue-down-au
   tongue-left-au
   tongue-right-au
   tongue-tilt-left-au
   tongue-tilt-right-au
   tongue-narrow-au
   tongue-wide-au
   tongue-tip-up-au
   tongue-tip-down-au])

(def tongue-au-caps
  ;; Per-AU caps keep the planner from over-driving optional tongue controls.
  ;; Tip/up cues can be more visible; width, tilt, and yaw cues stay subtle.
  {tongue-up-au 0.68
   tongue-down-au 0.48
   tongue-left-au 0.16
   tongue-right-au 0.16
   tongue-tilt-left-au 0.18
   tongue-tilt-right-au 0.18
   tongue-narrow-au 0.50
   tongue-wide-au 0.34
   tongue-tip-up-au 0.62
   tongue-tip-down-au 0.48})

(def intensity-eps 0.001)
(def tongue-group-gap-ms 86)
(def tongue-lead-ms 30)
(def tongue-release-ms 68)
(def tongue-attack-ms 42)
(def tongue-min-hold-ms 24)

(def tongue-visemes
  #{(:Ch_J visemes/canonical-visemes)
    (:K_G_H_NG visemes/canonical-visemes)
    (:T_L_D_N visemes/canonical-visemes)
    (:Th visemes/canonical-visemes)
    (:R visemes/canonical-visemes)
    (:S_Z visemes/canonical-visemes)})

(defn finite-number? [value]
  (and (number? value) (js/isFinite value)))

(defn class-name [value]
  (cond
    (keyword? value) (name value)
    (string? value) (str/lower-case value)
    (nil? value) nil
    :else (str/lower-case (str value))))

(defn event-classes [event]
  ;; Provider events sometimes arrive with only a viseme id. Fall back to the
  ;; canonical viseme class so Azure-style timelines and Web Speech fallback
  ;; timelines go through the same tongue rules.
  (let [from-event (into []
                         (keep class-name)
                         (concat (or (:phonemeClasses event) [])
                                 [(:phonemeClass event)]))
        fallback (into [] (keep class-name) (visemes/viseme-classes (:visemeId event)))]
    (set (if (seq from-event) from-event fallback))))

(defn has-class? [event class]
  (contains? (event-classes event) class))

(defn normalized-phoneme [event]
  (visemes/normalize-phoneme (:phoneme event)))

(defn start-ms [event]
  (state/number-or (:offsetMs event) 0))

(defn end-ms [event]
  (+ (start-ms event) (max 1 (state/number-or (:durationMs event) 1))))

(defn excluded-lip-only? [event]
  ;; Bilabials and labiodentals are lip/teeth actions. Keeping them out of the
  ;; tongue planner prevents visible tongue motion on P/B/M/F/V closures.
  (or (has-class? event "pause")
      (has-class? event "bilabial")
      (has-class? event "labiodental")
      (has-class? event "vowel")
      (has-class? event "diphthong")
      (has-class? event "glide")))

(defn clean-targets [targets]
  ;; Target maps are easier to reason about than a single scalar. Each phoneme
  ;; can request a different mix of existing Embody tongue AUs while the snippet
  ;; channel format stays unchanged.
  (into {}
        (keep (fn [[au target]]
                (let [value (state/clamp 0 1 (state/number-or target 0))]
                  (when (> value intensity-eps)
                    [au value]))))
        targets))

(defn tongue-au-targets [event]
  ;; These values are performance cues, not phonetic simulation. The goal is to
  ;; distinguish dental, alveolar, velar, and sibilant gestures with the AU
  ;; controls Embody already exposes, while staying subtle enough for speech.
  (let [phoneme (normalized-phoneme event)
        viseme-id (:visemeId event)]
    (clean-targets
     (cond
       (excluded-lip-only? event) {}

       (contains? #{"TH" "DH"} phoneme)
       {tongue-up-au 0.44
        tongue-wide-au 0.22
        tongue-tip-up-au 0.58}

       (contains? #{"T" "D"} phoneme)
       {tongue-up-au 0.46
        tongue-tip-up-au 0.50}

       (= "L" phoneme)
       {tongue-up-au 0.48
        tongue-tip-up-au 0.54
        tongue-wide-au 0.10}

       (= "N" phoneme)
       {tongue-up-au 0.38
        tongue-tip-up-au 0.36}

       (contains? #{"K" "G" "NG"} phoneme)
       {tongue-down-au 0.30
        tongue-narrow-au 0.22
        tongue-tip-down-au 0.24}

       (contains? #{"S" "Z"} phoneme)
       {tongue-up-au 0.16
        tongue-narrow-au 0.42
        tongue-tip-up-au 0.22}

       (contains? #{"SH" "ZH"} phoneme)
       {tongue-down-au 0.18
        tongue-narrow-au 0.44
        tongue-tip-down-au 0.22
        tongue-tilt-left-au 0.08}

       (contains? #{"CH" "JH"} phoneme)
       {tongue-up-au 0.24
        tongue-narrow-au 0.34
        tongue-tip-up-au 0.28
        tongue-tilt-right-au 0.08}

       (= "R" phoneme)
       {tongue-narrow-au 0.18
        tongue-tip-down-au 0.12
        tongue-tilt-left-au 0.08}

       (= viseme-id (:Th visemes/canonical-visemes))
       {tongue-up-au 0.40
        tongue-wide-au 0.18
        tongue-tip-up-au 0.52}

       (= viseme-id (:T_L_D_N visemes/canonical-visemes))
       {tongue-up-au 0.42
        tongue-tip-up-au 0.44}

       (= viseme-id (:Ch_J visemes/canonical-visemes))
       {tongue-up-au 0.22
        tongue-narrow-au 0.30
        tongue-tip-up-au 0.24}

       (= viseme-id (:K_G_H_NG visemes/canonical-visemes))
       {tongue-down-au 0.26
        tongue-narrow-au 0.20
        tongue-tip-down-au 0.20}

       (= viseme-id (:S_Z visemes/canonical-visemes))
       {tongue-up-au 0.12
        tongue-narrow-au 0.36
        tongue-tip-up-au 0.18}

       (= viseme-id (:R visemes/canonical-visemes))
       {tongue-narrow-au 0.16
        tongue-tip-down-au 0.10}

       (has-class? event "dental")
       {tongue-up-au 0.40
        tongue-tip-up-au 0.48}

       (has-class? event "sibilant")
       {tongue-narrow-au 0.34
        tongue-tip-up-au 0.16}

       (has-class? event "tongue")
       {tongue-up-au 0.30
        tongue-tip-up-au 0.26}

       (has-class? event "liquid")
       {tongue-narrow-au 0.14
        tongue-tip-down-au 0.10}

       (contains? tongue-visemes viseme-id)
       {tongue-up-au 0.16}

       :else {}))))

(defn tongue-event [event]
  (let [targets (tongue-au-targets event)]
    (when (seq targets)
      (assoc event :tongueTargets targets))))

(defn target-active? [targets au]
  (> (get targets au 0) intensity-eps))

(defn opposing-targets? [current next-event]
  ;; Do not collapse coronal and velar tongue gestures into one pose. A T/D/L/N
  ;; target wants tongue-up/tip-up; K/G/NG wants tongue-down/tip-down. Merging
  ;; those into one group drives opposing AUs at the same time and reads as a
  ;; snap. Compatible stacks, such as repeated sibilants, still group.
  (let [current-targets (:tongueTargets current)
        next-targets (:tongueTargets next-event)]
    (or (and (or (target-active? current-targets tongue-up-au)
                 (target-active? current-targets tongue-tip-up-au))
             (or (target-active? next-targets tongue-down-au)
                 (target-active? next-targets tongue-tip-down-au)))
        (and (or (target-active? current-targets tongue-down-au)
                 (target-active? current-targets tongue-tip-down-au))
             (or (target-active? next-targets tongue-up-au)
                 (target-active? next-targets tongue-tip-up-au))))))

(defn same-tongue-group? [current next-event]
  (let [gap (- (start-ms next-event) (end-ms current))]
    (and (<= gap tongue-group-gap-ms)
         (>= gap (- tongue-group-gap-ms))
         (not (opposing-targets? current next-event)))))

(defn raw-groups [events]
  ;; The grouping step makes the planner depend on a sequence of visemes rather
  ;; than individual phonemes. "texts" and "strengths" should become one coda
  ;; tongue gesture rather than K, T, TH, and S all firing separately.
  (let [ordered (->> (into [] (keep tongue-event) events)
                     (sort-by :offsetMs)
                     vec)]
    (loop [remaining ordered
           current []
           groups []]
      (cond
        (empty? remaining)
        (if (seq current) (conj groups current) groups)

        (empty? current)
        (recur (rest remaining) [(first remaining)] groups)

        (same-tongue-group? (last current) (first remaining))
        (recur (rest remaining) (conj current (first remaining)) groups)

        :else
        (recur (rest remaining) [(first remaining)] (conj groups current))))))

(defn boosted-targets [events]
  ;; Clusters with several tongue consonants get a small boost, but each AU is
  ;; capped independently so stacked consonants do not create a stiff pose.
  (let [raw (reduce (fn [merged event]
                      (merge-with max merged (:tongueTargets event)))
                    {}
                    events)
        boost (min 1.12 (+ 1 (* 0.04 (dec (count events)))))]
    (into {}
          (keep (fn [[au target]]
                  (let [cap (get tongue-au-caps au 0.68)
                        value (state/clamp 0 cap (* target boost))]
                    (when (> value intensity-eps)
                      [au value]))))
          raw)))

(defn make-group [events]
  (let [start (apply min (map start-ms events))
        end (apply max (map end-ms events))]
    {:startMs start
     :endMs end
     :durationMs (max 1 (- end start))
     :targets (boosted-targets events)
     :events (vec events)}))

(defn tongue-groups [events]
  (mapv make-group (raw-groups events)))

(defn push-frame [curve time-sec intensity]
  (if (or (not (finite-number? time-sec))
          (not (finite-number? intensity)))
    curve
    (let [frame {:time (max 0 time-sec)
                 :intensity (state/clamp 0 1 intensity)}
          previous (last curve)]
      (if (and previous (< (js/Math.abs (- (:time previous) (:time frame))) 0.001))
        (assoc-in curve [(dec (count curve)) :intensity] (:intensity frame))
        (conj curve frame)))))

(defn deduplicate-curve [curve]
  (let [frames (vec curve)]
    (if (<= (count frames) 1)
      frames
      (loop [remaining (rest frames)
             result [(first frames)]]
        (if (empty? remaining)
          (vec result)
          (let [curr (first remaining)
                prev (last result)
                intensity-changed? (> (js/Math.abs (- (:intensity curr) (:intensity prev))) intensity-eps)
                time-changed? (> (js/Math.abs (- (:time curr) (:time prev))) 0.001)]
            (recur (rest remaining)
                   (if (or intensity-changed? time-changed?)
                     (conj result curr)
                     result))))))))

(defn scaled-target [group au scale]
  ;; Keep the per-AU cap after the user scale is applied. A tongueScale above
  ;; 1 should make tongue motion easier to see, but it should not turn subtle
  ;; width/tilt/yaw controls into full-strength snapped poses.
  (let [cap (get tongue-au-caps au 0.68)]
    (state/clamp 0 cap (* scale (get-in group [:targets au] 0)))))

(defn build-tongue-curve-for-au [groups au tongue-scale]
  ;; Iterate every tongue group, including groups where this AU's target is 0.
  ;; That gives the planner enough context to ramp an active AU down before the
  ;; next opposing tongue gesture begins. Filtering to active groups only made
  ;; an AU release on its own stale end time, which could leave tongue-up active
  ;; while the next group was already driving tongue-down/narrow.
  (let [scale (state/clamp 0 2 (state/number-or tongue-scale 1))
        groups (vec groups)
        has-active? (some #(> (scaled-target % au scale) intensity-eps) groups)]
    (if (or (<= scale intensity-eps) (not has-active?))
      []
      (loop [index 0
             curve []]
        (if (>= index (count groups))
          (->> curve (sort-by :time) deduplicate-curve vec)
          (let [group (get groups index)
                previous (get groups (dec index))
                next-group (get groups (inc index))
                previous-target (if previous (scaled-target previous au scale) 0)
                next-target (if next-group (scaled-target next-group au scale) 0)
                start-sec (/ (max 0 (- (:startMs group) tongue-lead-ms)) 1000)
                group-start-sec (/ (:startMs group) 1000)
                end-sec (/ (+ (:endMs group) tongue-release-ms) 1000)
                target (scaled-target group au scale)
                attack-sec (/ (min tongue-attack-ms (max 8 (* (:durationMs group) 0.35))) 1000)
                hold-end-sec (/ (max (+ (:startMs group) tongue-min-hold-ms)
                                     (- (:endMs group) (* tongue-release-ms 0.4)))
                                1000)
                previous-end-sec (if previous (/ (:endMs previous) 1000) 0)
                next-start-sec (when next-group (/ (:startMs next-group) 1000))
                starts-after-gap? (or (nil? previous)
                                      (> (- start-sec previous-end-sec) (/ tongue-group-gap-ms 1000))
                                      (<= previous-target intensity-eps))
                curve-a (if (and (> target intensity-eps) starts-after-gap?)
                          (push-frame curve start-sec 0)
                          curve)
                curve-b (if (> target intensity-eps)
                          (push-frame curve-a (+ group-start-sec attack-sec) target)
                          (if (> previous-target intensity-eps)
                            (push-frame curve-a group-start-sec 0)
                            curve-a))
                connected? (and next-group
                                (<= (- (:startMs next-group) (:endMs group)) tongue-group-gap-ms))
                release-end-sec (if (and connected?
                                         (<= next-target intensity-eps)
                                         next-start-sec)
                                  (max hold-end-sec next-start-sec)
                                  end-sec)]
            (recur (inc index)
                   (cond
                     (<= target intensity-eps)
                     curve-b

                     (and connected? (> next-target intensity-eps))
                     (push-frame curve-b (max hold-end-sec
                                              (/ (- (:startMs next-group) tongue-lead-ms) 1000))
                                 target)

                     :else
                     (-> curve-b
                         (push-frame hold-end-sec target)
                         (push-frame release-end-sec 0))))))))))

(defn build-tongue-curve [events tongue-scale]
  ;; Backward-compatible helper for tests and callers that only care about the
  ;; original tongue-up channel.
  (build-tongue-curve-for-au (tongue-groups events) tongue-up-au tongue-scale))

(defn build-tongue-curves [events tongue-scale]
  (let [groups (tongue-groups events)
        active-aus (->> groups
                        (mapcat #(keys (:targets %)))
                        set
                        (sort-by #(js/parseInt % 10)))]
    (into {}
          (keep (fn [au]
                  (let [curve (build-tongue-curve-for-au groups au tongue-scale)]
                    (when (seq curve)
                      [au curve]))))
          active-aus)))
