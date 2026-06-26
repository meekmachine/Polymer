(ns polymer.vocal.tongue
  (:require [clojure.string :as str]
            [polymer.vocal.state :as state]
            [polymer.vocal.visemes :as visemes]))

;; Tongue planning is a third independent vocal control surface beside lips and
;; jaw. The input is the same normalized viseme/phoneme sequence used by those
;; planners; the output is an ordinary AU curve. Embody already maps AU 37 to
;; the CC4 TONGUE pitch composite, so this stays on the same scheduled snippet
;; path as the rest of lipsync instead of introducing a host-specific side path.

(def tongue-up-au "37")
(def intensity-eps 0.001)
(def tongue-group-gap-ms 78)
(def tongue-lead-ms 18)
(def tongue-release-ms 34)
(def tongue-attack-ms 20)
(def tongue-min-hold-ms 14)

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
  (let [from-event (remove nil?
                           (concat (map class-name (or (:phonemeClasses event) []))
                                   [(class-name (:phonemeClass event))]))
        fallback (map class-name (visemes/viseme-classes (:visemeId event)))]
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

(defn base-target [event]
  ;; Target values are intentionally lower than manual AU sliders. Lipsync needs
  ;; a subtle tongue cue that supports articulation, not a full tongue pose.
  (let [phoneme (normalized-phoneme event)
        viseme-id (:visemeId event)]
    (cond
      (excluded-lip-only? event) 0
      (contains? #{"TH" "DH"} phoneme) 0.58
      (contains? #{"T" "D"} phoneme) 0.52
      (= "L" phoneme) 0.50
      (= "N" phoneme) 0.44
      (contains? #{"CH" "JH"} phoneme) 0.42
      (contains? #{"K" "G" "NG"} phoneme) 0.30
      (contains? #{"S" "Z" "SH" "ZH"} phoneme) 0.22
      (= "R" phoneme) 0.14
      (= viseme-id (:Th visemes/canonical-visemes)) 0.52
      (= viseme-id (:T_L_D_N visemes/canonical-visemes)) 0.46
      (= viseme-id (:Ch_J visemes/canonical-visemes)) 0.38
      (= viseme-id (:K_G_H_NG visemes/canonical-visemes)) 0.28
      (= viseme-id (:S_Z visemes/canonical-visemes)) 0.20
      (= viseme-id (:R visemes/canonical-visemes)) 0.12
      (has-class? event "dental") 0.50
      (has-class? event "tongue") 0.36
      (has-class? event "sibilant") 0.20
      (has-class? event "liquid") 0.16
      (contains? tongue-visemes viseme-id) 0.18
      :else 0)))

(defn tongue-event [event]
  (let [target (state/clamp 0 1 (base-target event))]
    (when (> target intensity-eps)
      (assoc event :tongueTarget target))))

(defn same-tongue-group? [current next-event]
  (let [gap (- (start-ms next-event) (end-ms current))]
    (and (<= gap tongue-group-gap-ms)
         (>= gap (- tongue-group-gap-ms)))))

(defn raw-groups [events]
  ;; The grouping step is what makes the planner depend on a sequence of
  ;; visemes instead of individual events. "texts" and "strengths" should become
  ;; one coda tongue gesture rather than K, T, TH, and S all firing separately.
  (let [ordered (->> events
                     (keep tongue-event)
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

(defn group-target [events]
  ;; Clusters with several tongue consonants get a small boost, but the value is
  ;; capped so stacked consonants do not create a stiff held tongue pose.
  (let [raw-max (apply max 0 (map :tongueTarget events))
        boost (min 1.12 (+ 1 (* 0.04 (dec (count events)))))]
    (state/clamp 0 0.68 (* raw-max boost))))

(defn make-group [events]
  (let [start (apply min (map start-ms events))
        end (apply max (map end-ms events))]
    {:startMs start
     :endMs end
     :durationMs (max 1 (- end start))
     :target (group-target events)
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

(defn build-tongue-curve [events tongue-scale]
  (let [scale (state/clamp 0 2 (state/number-or tongue-scale 1))
        groups (tongue-groups events)]
    (if (or (<= scale intensity-eps) (empty? groups))
      []
      (loop [index 0
             curve []]
        (if (>= index (count groups))
          (->> curve (sort-by :time) deduplicate-curve vec)
          (let [group (get groups index)
                previous (get groups (dec index))
                next-group (get groups (inc index))
                start-sec (/ (max 0 (- (:startMs group) tongue-lead-ms)) 1000)
                group-start-sec (/ (:startMs group) 1000)
                end-sec (/ (+ (:endMs group) tongue-release-ms) 1000)
                target (state/clamp 0 1 (* scale (:target group)))
                attack-sec (/ (min tongue-attack-ms (max 8 (* (:durationMs group) 0.35))) 1000)
                hold-end-sec (/ (max (+ (:startMs group) tongue-min-hold-ms)
                                     (- (:endMs group) (* tongue-release-ms 0.4)))
                                1000)
                previous-end-sec (if previous (/ (:endMs previous) 1000) 0)
                starts-after-gap? (or (nil? previous)
                                      (> (- start-sec previous-end-sec) (/ tongue-group-gap-ms 1000)))
                curve-a (if starts-after-gap? (push-frame curve start-sec 0) curve)
                curve-b (push-frame curve-a (+ group-start-sec attack-sec) target)
                connected? (and next-group
                                (<= (- (:startMs next-group) (:endMs group)) tongue-group-gap-ms))]
            (recur (inc index)
                   (if connected?
                     (push-frame curve-b (max hold-end-sec
                                              (/ (- (:startMs next-group) tongue-lead-ms) 1000))
                                 target)
                     (-> curve-b
                         (push-frame hold-end-sec target)
                         (push-frame end-sec 0))))))))))

(defn build-tongue-curves [events tongue-scale]
  (let [curve (build-tongue-curve events tongue-scale)]
    (if (empty? curve)
      {}
      {tongue-up-au curve})))
