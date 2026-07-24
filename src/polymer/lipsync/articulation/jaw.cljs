(ns polymer.lipsync.articulation.jaw
  (:require [clojure.string :as str]
            [polymer.lipsync.state :as state]
            [polymer.lipsync.articulation.visemes :as visemes]))

;; The jaw planner is deliberately separate from lip/viseme curve assembly.
;; JALI-style lip sync treats mouth shape and jaw drop as related but distinct
;; control axes: a bilabial can close the lips without opening the jaw, and a
;; vowel nucleus can keep the jaw open while the lips travel through a diphthong.
;;
;; Inputs are normalized viseme timeline events. Outputs are jaw-activation
;; keyframes; snippet assembly routes those keyframes to Embody lip-sync control
;; 103 (bone-only jaw open via profile bindings). There are no host side effects
;; here: no audio, DOM, scheduler, engine handle, UI state, or provider
;; credential access.

(def intensity-eps 0.001)
(def jaw-long-gap-ms 90)
(def jaw-group-gap-ms 65)
(def jaw-transition-lead-ms 24)
(def jaw-vowel-attack-ms 42)
(def jaw-vowel-release-ms 45)
(def jaw-consonant-attack-ms 18)
(def jaw-consonant-release-ms 28)
(def jaw-bridge-ratio 0.55)

(defn finite-number? [value]
  (and (number? value) (js/isFinite value)))

(defn class-name [value]
  (cond
    (keyword? value) (name value)
    (string? value) (str/lower-case value)
    (nil? value) nil
    :else (str/lower-case (str value))))

(defn event-classes [event]
  ;; Provider timelines can be lossy, so the event may carry both a primary
  ;; phoneme class and a full class set. If either is absent, fall back to the
  ;; canonical viseme slot class so planner rules still behave conservatively.
  (let [from-event (into []
                         (keep class-name)
                         (concat (or (:phonemeClasses event) [])
                                 [(:phonemeClass event)]))
        fallback (into [] (keep class-name) (visemes/viseme-classes (:visemeId event)))]
    (set (if (seq from-event) from-event fallback))))

(defn has-class? [event class]
  (contains? (event-classes event) class))

(defn raw-target [event]
  (state/clamp 0 2
               (state/number-or (:jawActivation event)
                                (visemes/jaw-activation-for-viseme (:visemeId event)))))

(defn start-ms [event]
  (state/number-or (:offsetMs event) 0))

(defn end-ms [event]
  (+ (start-ms event) (max 1 (state/number-or (:durationMs event) 1))))

(defn pause-event? [event]
  (or (has-class? event "pause")
      (str/starts-with? (or (:phoneme event) "") "PAUSE_")))

(defn bilabial? [event]
  (has-class? event "bilabial"))

(defn narrow-fricative? [event]
  (or (has-class? event "sibilant")
      (has-class? event "labiodental")
      (has-class? event "dental")
      (has-class? event "fricative")))

(defn tongue-consonant? [event]
  (or (has-class? event "tongue")
      (has-class? event "obstruent")
      (has-class? event "nasal")
      (has-class? event "liquid")))

(defn vocalic-jaw-event? [event]
  ;; Diphthong expansion marks the secondary lip target with "diphthong".
  ;; Azure W/UW events often arrive only as the W_OO viseme; treat those as
  ;; vocalic only when their jaw target is the rounded vowel/glide amount, not
  ;; when fallback text generated a low consonantal W.
  (or (has-class? event "vowel")
      (has-class? event "diphthong")
      (and (= (:visemeId event) (:W_OO visemes/canonical-visemes))
           (>= (raw-target event) 0.28))))

(defn event-kind [event]
  (cond
    (pause-event? event) :pause
    (vocalic-jaw-event? event) :vocalic
    :else :consonant))

(defn consonant-target [events]
  ;; Consonants should not make the jaw flap. Closures and narrow consonants are
  ;; mostly lip/tongue business, so the jaw stays low and stable across the
  ;; whole consonant cluster.
  (let [events (vec events)
        raw-max (apply max 0 (map raw-target events))]
    (cond
      (every? bilabial? events) 0
      (some bilabial? events) (min raw-max 0.08)
      (every? narrow-fricative? events) (min raw-max 0.08)
      (some narrow-fricative? events) (min raw-max 0.10)
      (every? tongue-consonant? events) (min raw-max 0.14)
      :else (min raw-max 0.16))))

(defn group-target [kind events]
  (case kind
    :pause 0
    :vocalic (apply max 0 (map raw-target events))
    :consonant (consonant-target events)))

(defn same-jaw-group? [current next-event]
  (let [kind (event-kind current)
        next-kind (event-kind next-event)
        gap (- (start-ms next-event) (end-ms current))]
    (and (= kind next-kind)
         (<= gap jaw-group-gap-ms)
         (>= gap (- jaw-group-gap-ms)))))

(defn raw-groups [events]
  (let [ordered (vec (sort-by :offsetMs events))]
    (loop [remaining ordered
           current []
           groups []]
      (cond
        (empty? remaining)
        (if (seq current) (conj groups current) groups)

        (empty? current)
        (recur (rest remaining) [(first remaining)] groups)

        (same-jaw-group? (last current) (first remaining))
        (recur (rest remaining) (conj current (first remaining)) groups)

        :else
        (recur (rest remaining) [(first remaining)] (conj groups current))))))

(defn make-group [events]
  (let [kind (event-kind (first events))
        start (apply min (map start-ms events))
        end (apply max (map end-ms events))]
    {:kind kind
     :startMs start
     :endMs end
     :durationMs (max 1 (- end start))
     :target (group-target kind events)
     :events (vec events)}))

(defn closure-group? [group]
  (some bilabial? (:events group)))

(defn bridgeable-consonant-group? [group previous next-group]
  ;; A short tongue-only consonant between two vowels should not close the jaw
  ;; and reopen it as a separate visual beat. Bilabials and narrow fricatives are
  ;; excluded because they need visible closure/narrowing.
  (and (= :consonant (:kind group))
       (= :vocalic (:kind previous))
       (= :vocalic (:kind next-group))
       (<= (:durationMs group) 90)
       (not (closure-group? group))
       (not-any? narrow-fricative? (:events group))))

(defn contextualize-group [groups index group]
  (let [previous (get groups (dec index))
        next-group (get groups (inc index))]
    (if (bridgeable-consonant-group? group previous next-group)
      (let [bridge-target (* jaw-bridge-ratio (min (:target previous) (:target next-group)))]
        (update group :target #(max % bridge-target)))
      group)))

(defn jaw-groups [events]
  (let [groups (mapv make-group (raw-groups events))]
    (mapv #(contextualize-group groups %1 %2) (range) groups)))

(defn push-frame [curve time-sec intensity]
  (if (or (not (finite-number? time-sec))
          (not (finite-number? intensity)))
    curve
    (let [frame {:time (max 0 time-sec)
                 :intensity (state/clamp 0 2 intensity)}
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

(defn group-attack-sec [group]
  (/ (min (if (= :vocalic (:kind group)) jaw-vowel-attack-ms jaw-consonant-attack-ms)
          (max 6 (* (:durationMs group) 0.35)))
     1000))

(defn group-release-sec [group]
  (/ (min (if (= :vocalic (:kind group)) jaw-vowel-release-ms jaw-consonant-release-ms)
          (max 10 (* (:durationMs group)
                     (if (= :vocalic (:kind group)) 0.22 0.45))))
     1000))

(defn connected-to-next? [group next-group]
  (and next-group
       (<= (- (:startMs next-group) (:endMs group)) jaw-long-gap-ms)))

(defn build-jaw-curve [events jaw-scale]
  (let [scale (state/clamp 0 2 (state/number-or jaw-scale 1))
        groups (jaw-groups events)
        has-jaw? (some #(> (* scale (:target %)) intensity-eps) groups)]
    (if-not has-jaw?
      []
      (loop [index 0
             curve []]
        (if (>= index (count groups))
          (->> curve (sort-by :time) deduplicate-curve vec)
          (let [group (get groups index)
                previous (get groups (dec index))
                next-group (get groups (inc index))
                start-sec (/ (:startMs group) 1000)
                end-sec (/ (:endMs group) 1000)
                target (* scale (:target group))
                attack-sec (group-attack-sec group)
                release-sec (group-release-sec group)
                previous-end-sec (if previous (/ (:endMs previous) 1000) 0)
                starts-after-gap? (or (nil? previous)
                                      (> (- start-sec previous-end-sec) (/ jaw-long-gap-ms 1000)))
                curve-a (if starts-after-gap? (push-frame curve start-sec 0) curve)
                peak-time (if (> target intensity-eps)
                            (+ start-sec attack-sec)
                            start-sec)
                curve-b (push-frame curve-a peak-time target)
                connected? (connected-to-next? group next-group)]
            (recur (inc index)
                   (if connected?
                     (let [next-start-sec (/ (:startMs next-group) 1000)
                           transition-time (max peak-time
                                                (- next-start-sec (/ jaw-transition-lead-ms 1000)))]
                       (push-frame curve-b transition-time target))
                     (-> curve-b
                         (push-frame (max peak-time (- end-sec release-sec)) target)
                         (push-frame end-sec 0))))))))))
