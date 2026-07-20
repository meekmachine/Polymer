(ns polymer.lipsync.articulation.lip
  (:require [clojure.string :as str]
            [polymer.lipsync.articulation.modulation :as modulation]
            [polymer.lipsync.articulation.visemes :as visemes]
            [polymer.lipsync.state :as state]))

;; Pure JALI-style lip action planner. Consumes normalized timeline events and
;; emits sparse viseme/lip AU curves only. Jaw and tongue stay in their own
;; planners; snippet assembly composes the three.

(def labiodental-contact-au "32")
(def labiodental-press-au "24")
(def intensity-eps 0.001)
(def envelope-shoulder-ratio 0.55)
(def envelope-shoulder-intensity 0.62)
(def lip-dominant-cap 1)
;; Exclusive viseme morphs: at any sample only one mouth-shape morph may be
;; active. Jaw (bone) and tongue (AU) stay independent and may run alongside.
(def exclusive-active-threshold 0.05)
(def plosive-preclose-sec 0.028)
(def plosive-hold-sec 0.022)
(def plosive-release-sec 0.018)
;; Labiodental contact AUs stack on the same mouth as F_V. Keep them off by
;; default so F_V remains a single readable morph; jaw/tongue still move freely.
(def emit-labiodental-au-overlays? false)
(def labiodental-contact-peak 0.24)
(def labiodental-press-peak 0.12)

;; Keep carry/anticipation short. Longer overlap was the main reason multiple
;; viseme morphs stayed lit and washed shape contrast.
(def coarticulation-by-class
  {:bilabial {:carrySec 0 :anticipateSec 0}
   :labiodental {:carrySec 0.002 :anticipateSec 0.004}
   :sibilant {:carrySec 0.002 :anticipateSec 0.003}
   :fricative {:carrySec 0.002 :anticipateSec 0.004}
   :vowel {:carrySec 0.004 :anticipateSec 0.006}
   :liquid {:carrySec 0.004 :anticipateSec 0.006}
   :glide {:carrySec 0.006 :anticipateSec 0.008}
   :lip-heavy {:carrySec 0.010 :anticipateSec 0.012}
   :tongue {:carrySec 0.002 :anticipateSec 0.003}
   :default {:carrySec 0.003 :anticipateSec 0.004}})
(def vowel-visemes
  #{(:AE visemes/canonical-visemes)
    (:Ah visemes/canonical-visemes)
    (:EE visemes/canonical-visemes)
    (:Er visemes/canonical-visemes)
    (:Ih visemes/canonical-visemes)
    (:Oh visemes/canonical-visemes)})

(defn finite-number? [value]
  (and (number? value) (js/isFinite value)))

(defn class-name [value]
  (cond
    (keyword? value) (name value)
    (string? value) (str/lower-case value)
    (nil? value) nil
    :else (str/lower-case (str value))))

(defn event-classes [event]
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

(defn pause-event? [event]
  ;; Pauses are timing/neutralization data. Emitting B_M_P for them creates
  ;; artificial mouth beats between words or after punctuation. Never key off
  ;; viseme id 0 here: that is the canonical AE slot, not silence.
  (or (has-class? event "pause")
      (str/starts-with? (or (:phoneme event) "") "PAUSE_")
      (contains? #{"sil" "pau" "silence" "SIL" "PAU"} (normalized-phoneme event))))

(defn vowel-event? [event]
  (or (has-class? event "vowel")
      (contains? vowel-visemes (:visemeId event))))

(defn bilabial-event? [event]
  (or (has-class? event "bilabial")
      (= (:visemeId event) (:B_M_P visemes/canonical-visemes))))

(defn bilabial-plosive? [event]
  ;; M shares the B_M_P viseme but should not get a hard burst release. Only
  ;; known P/B phonemes, or class metadata that says bilabial obstruent without
  ;; nasal, get the plosive closure plan.
  (let [phoneme (normalized-phoneme event)
        classes (event-classes event)]
    (and (= (:visemeId event) (:B_M_P visemes/canonical-visemes))
         (not (contains? classes "nasal"))
         (or (contains? #{"P" "B"} phoneme)
             (and (contains? classes "bilabial")
                  (contains? classes "obstruent"))))))

(defn labiodental-event? [event]
  (or (= (:visemeId event) (:F_V visemes/canonical-visemes))
      (has-class? event "labiodental")))

(defn sibilant-event? [event]
  (or (= (:visemeId event) (:S_Z visemes/canonical-visemes))
      (has-class? event "sibilant")))

(defn lip-heavy-event? [event]
  (or (has-class? event "lip-heavy")
      (= (:visemeId event) (:W_OO visemes/canonical-visemes))))

(defn lip-planner-class [event]
  (cond
    (bilabial-event? event) :bilabial
    (labiodental-event? event) :labiodental
    (sibilant-event? event) :sibilant
    (lip-heavy-event? event) :lip-heavy
    (vowel-event? event) :vowel
    (has-class? event "liquid") :liquid
    (has-class? event "glide") :glide
    (has-class? event "fricative") :fricative
    (or (has-class? event "tongue") (has-class? event "obstruent")) :tongue
    :else :default))

(defn viseme-class [viseme-id]
  (cond
    (= viseme-id (:B_M_P visemes/canonical-visemes)) :bilabial
    (= viseme-id (:W_OO visemes/canonical-visemes)) :lip-heavy
    (contains? vowel-visemes viseme-id) :vowel
    (= viseme-id (:S_Z visemes/canonical-visemes)) :sibilant
    (= viseme-id (:F_V visemes/canonical-visemes)) :labiodental
    (#{(:Ch_J visemes/canonical-visemes)
       (:Th visemes/canonical-visemes)} viseme-id) :fricative
    (#{(:K_G_H_NG visemes/canonical-visemes)
       (:T_L_D_N visemes/canonical-visemes)} viseme-id) :tongue
    (= viseme-id (:R visemes/canonical-visemes)) :liquid
    :else :default))

(defn envelope-profile [event-or-viseme-id]
  ;; Peak contrast matters once only one morph can win: open Ah/AE must read
  ;; differently from narrow EE/Ih, rounded Oh/W, and light sibilants. Timings
  ;; are visual, not acoustic.
  (let [viseme-id (if (map? event-or-viseme-id)
                    (:visemeId event-or-viseme-id)
                    event-or-viseme-id)
        event (when (map? event-or-viseme-id) event-or-viseme-id)
        planner-class (if event (lip-planner-class event) (viseme-class viseme-id))]
    (cond
      (= planner-class :bilabial) {:attackSec 0.004 :releaseSec 0.006 :peak 1.0}
      (= planner-class :lip-heavy) {:attackSec 0.038 :releaseSec 0.048 :peak 0.98}
      (= planner-class :labiodental) {:attackSec 0.022 :releaseSec 0.032 :peak 0.94}
      (= planner-class :sibilant) {:attackSec 0.018 :releaseSec 0.026 :peak 0.52}
      (= viseme-id (:Ah visemes/canonical-visemes)) {:attackSec 0.034 :releaseSec 0.044 :peak 1.0}
      (= viseme-id (:AE visemes/canonical-visemes)) {:attackSec 0.032 :releaseSec 0.042 :peak 0.98}
      (= viseme-id (:Oh visemes/canonical-visemes)) {:attackSec 0.036 :releaseSec 0.046 :peak 0.96}
      (= viseme-id (:EE visemes/canonical-visemes)) {:attackSec 0.028 :releaseSec 0.036 :peak 0.82}
      (= viseme-id (:Ih visemes/canonical-visemes)) {:attackSec 0.026 :releaseSec 0.034 :peak 0.70}
      (= viseme-id (:Er visemes/canonical-visemes)) {:attackSec 0.030 :releaseSec 0.038 :peak 0.66}
      (= viseme-id (:Th visemes/canonical-visemes)) {:attackSec 0.022 :releaseSec 0.032 :peak 0.68}
      (= viseme-id (:Ch_J visemes/canonical-visemes)) {:attackSec 0.024 :releaseSec 0.034 :peak 0.74}
      (= viseme-id (:K_G_H_NG visemes/canonical-visemes)) {:attackSec 0.020 :releaseSec 0.030 :peak 0.42}
      (= viseme-id (:T_L_D_N visemes/canonical-visemes)) {:attackSec 0.024 :releaseSec 0.034 :peak 0.58}
      (= viseme-id (:R visemes/canonical-visemes)) {:attackSec 0.028 :releaseSec 0.036 :peak 0.72}
      (= planner-class :vowel) {:attackSec 0.032 :releaseSec 0.042 :peak 0.90}
      (= planner-class :fricative) {:attackSec 0.022 :releaseSec 0.032 :peak 0.68}
      (= planner-class :tongue) {:attackSec 0.024 :releaseSec 0.034 :peak 0.58}
      (= planner-class :liquid) {:attackSec 0.028 :releaseSec 0.036 :peak 0.72}
      (= planner-class :glide) {:attackSec 0.032 :releaseSec 0.040 :peak 0.88}
      :else {:attackSec 0.026 :releaseSec 0.034 :peak 0.80})))
(defn event-intensity [event config]
  (state/number-or (:plannedLipIntensity event)
                   (modulation/event-lip-intensity event config)))

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
                next-frame (second remaining)
                intensity-changed? (> (js/Math.abs (- (:intensity curr) (:intensity prev))) intensity-eps)
                ends-plateau? (and next-frame
                                   (> (js/Math.abs (- (:intensity next-frame) (:intensity curr))) intensity-eps))
                keep? (or intensity-changed? ends-plateau? (= 1 (count remaining)))]
            (recur (rest remaining) (if keep? (conj result curr) result))))))))

(defn scale-lip-intensity [value intensity]
  (let [normalized (state/clamp 0 lip-dominant-cap value)
        scale (max 0 (state/number-or intensity 1))]
    (cond
      (<= (js/Math.abs (- scale 1)) intensity-eps) normalized
      (<= scale 1) (* normalized scale)
      :else (- 1 (js/Math.pow (- 1 normalized) scale)))))

(defn scale-curve-intensity [curve intensity]
  (mapv (fn [frame]
          (assoc frame :intensity (scale-lip-intensity (:intensity frame) intensity)))
        curve))

(defn build-viseme-curve [event intensity]
  (let [viseme-id (:visemeId event)
        start-ms (:offsetMs event)
        duration-ms (:durationMs event)
        start-sec (/ start-ms 1000)
        duration-sec (/ duration-ms 1000)
        end-sec (+ start-sec duration-sec)
        profile (envelope-profile event)
        peak (:peak profile)
        ;; Lip-heavy sounds start early and end late relative to neighbors.
        lead-sec (if (lip-heavy-event? event) 0.018 0)
        trail-sec (if (lip-heavy-event? event) 0.016 0)
        shaped-start (max 0 (- start-sec lead-sec))
        shaped-end (+ end-sec trail-sec)
        shaped-duration (- shaped-end shaped-start)]
    (if (<= shaped-duration 0)
      []
      (let [attack-sec (min (:attackSec profile) (* shaped-duration 0.45))
            release-sec (min (:releaseSec profile) (* shaped-duration 0.45))]
        (scale-curve-intensity
         (if (<= shaped-duration (+ attack-sec release-sec 0.002))
           [{:time shaped-start :intensity 0}
            {:time (+ shaped-start (* shaped-duration 0.5)) :intensity peak}
            {:time shaped-end :intensity 0}]
           (let [ramp-up-end (+ shaped-start attack-sec)
                 hold-end (- shaped-end release-sec)]
             (if (= (lip-planner-class event) :bilabial)
               [{:time shaped-start :intensity 0}
                {:time ramp-up-end :intensity peak}
                {:time hold-end :intensity peak}
                {:time shaped-end :intensity 0}]
               [{:time shaped-start :intensity 0}
                {:time (+ shaped-start (* attack-sec envelope-shoulder-ratio))
                 :intensity (* peak envelope-shoulder-intensity)}
                {:time ramp-up-end :intensity peak}
                {:time hold-end :intensity peak}
                {:time (+ hold-end (* release-sec (- 1 envelope-shoulder-ratio)))
                 :intensity (* peak envelope-shoulder-intensity)}
                {:time shaped-end :intensity 0}])))
         intensity)))))

(defn build-plosive-closure-curve [event next-event intensity]
  (let [start-sec (/ (:offsetMs event) 1000)
        duration-sec (/ (:durationMs event) 1000)
        end-sec (+ start-sec duration-sec)
        next-start-sec (when next-event (/ (:offsetMs next-event) 1000))
        release-end-sec (if (and next-start-sec (vowel-event? next-event))
                          (max start-sec next-start-sec)
                          end-sec)
        close-start-sec (max 0 (- start-sec plosive-preclose-sec))
        close-end-sec (min (+ start-sec 0.010) release-end-sec)
        hold-end-sec (max close-end-sec
                          (min (- release-end-sec 0.004)
                               (+ close-end-sec plosive-hold-sec)
                               (- end-sec plosive-release-sec)))
        peak (:peak (envelope-profile event))]
    (scale-curve-intensity
     (-> [{:time close-start-sec :intensity 0}
          {:time close-end-sec :intensity peak}
          {:time hold-end-sec :intensity peak}
          {:time release-end-sec :intensity 0}]
         deduplicate-curve
         vec)
     intensity)))

(defn build-event-viseme-curve [event next-event intensity]
  (cond
    (pause-event? event) []
    (bilabial-plosive? event) (build-plosive-closure-curve event next-event intensity)
    :else (build-viseme-curve event intensity)))

(defn merge-curves [existing incoming]
  (->> (concat existing incoming)
       (sort-by :time)
       deduplicate-curve
       vec))

(defn build-labiodental-au-curve [event peak intensity]
  (let [start-sec (/ (:offsetMs event) 1000)
        duration-sec (/ (:durationMs event) 1000)
        end-sec (+ start-sec duration-sec)
        attack-sec (min 0.024 (* duration-sec 0.35))
        release-sec (min 0.034 (* duration-sec 0.40))
        hold-end-sec (max (+ start-sec attack-sec)
                          (- end-sec release-sec))]
    (scale-curve-intensity
     [{:time start-sec :intensity 0}
      {:time (+ start-sec attack-sec) :intensity peak}
      {:time hold-end-sec :intensity peak}
      {:time end-sec :intensity 0}]
     intensity)))

(defn build-labiodental-curves [events config]
  ;; F_V is already a mouth morph. Emitting AU24/AU32 on top stacked a second
  ;; (and third) mouth morph and flattened labiodental contrast. Keep overlays
  ;; behind an explicit flag for rigs that need them without F_V.
  (if-not emit-labiodental-au-overlays?
    {}
    (reduce (fn [curves event]
              (if (and (labiodental-event? event) (not (pause-event? event)))
                (let [intensity (event-intensity event config)]
                  (-> curves
                      (update labiodental-contact-au
                              #(merge-curves (or % [])
                                             (build-labiodental-au-curve event
                                                                         labiodental-contact-peak
                                                                         intensity)))
                      (update labiodental-press-au
                              #(merge-curves (or % [])
                                             (build-labiodental-au-curve event
                                                                         labiodental-press-peak
                                                                         intensity)))))
                curves))
            {}
            events)))

(defn update-last-frame-time [curve f]
  (let [frames (vec curve)
        idx (dec (count frames))]
    (if (neg? idx)
      frames
      (assoc-in frames [idx :time] (f (get-in frames [idx :time]))))))

(defn update-first-frame-time [curve f]
  (let [frames (vec curve)]
    (if (empty? frames)
      frames
      (assoc-in frames [0 :time] (f (get-in frames [0 :time]))))))

(defn coarticulation-amounts [event]
  (get coarticulation-by-class (lip-planner-class event)
       (:default coarticulation-by-class)))

(defn apply-coarticulation [curves events]
  ;; Class-specific carry/anticipation. Bilabial closures never blend. Open
  ;; vowels must not smear through a following closure, and lip-heavy sounds get
  ;; longer hysteresis than liquids/glides.
  (loop [index 0
         result curves]
    (if (>= index (dec (count events)))
      result
      (let [current (get events index)
            next-event (get events (inc index))
            current-class (lip-planner-class current)
            next-class (lip-planner-class next-event)
            current-end (+ (:offsetMs current) (:durationMs current))
            next-start (:offsetMs next-event)
            gap (- next-start current-end)
            current-key (str (:visemeId current))
            next-key (str (:visemeId next-event))
            current-amounts (coarticulation-amounts current)
            next-amounts (coarticulation-amounts next-event)
            blend? (and (< gap 50)
                        (> gap -30)
                        (not= current-class :bilabial)
                        (not= next-class :bilabial)
                        (not (pause-event? current))
                        (not (pause-event? next-event)))
            ;; Vowels may anticipate the next open shape, but never carry into a
            ;; closure/labiodental that needs a crisp contact.
            carry? (and (contains? #{:vowel :liquid :glide :lip-heavy} current-class)
                        (not (contains? #{:bilabial :labiodental :sibilant} next-class)))
            anticipate? (and (contains? #{:vowel :liquid :glide :lip-heavy} next-class)
                             (not (contains? #{:bilabial :labiodental} current-class)))]
        (recur (inc index)
               (cond-> result
                 (and blend? carry? (get result current-key))
                 (update current-key update-last-frame-time
                         #(min (max % (/ next-start 1000))
                               (+ % (:carrySec current-amounts))))

                 (and blend? anticipate? (get result next-key))
                 (update next-key update-first-frame-time
                         #(max 0 (- % (:anticipateSec next-amounts))))))))))

(defn sample-curve-at [curve time]
  (let [frames (vec curve)]
    (cond
      (empty? frames) 0
      (<= time (:time (first frames))) (:intensity (first frames))
      (>= time (:time (last frames))) (:intensity (last frames))
      :else
      (loop [index 0]
        (if (>= index (dec (count frames)))
          0
          (let [a (get frames index)
                b (get frames (inc index))]
            (if (and (>= time (:time a)) (<= time (:time b)))
              (let [span (max 0.000001 (- (:time b) (:time a)))
                    progress (/ (- time (:time a)) span)]
                (+ (:intensity a) (* (- (:intensity b) (:intensity a)) progress)))
              (recur (inc index)))))))))

(defn rounded-sample-time [time]
  (/ (js/Math.round (* time 1000)) 1000))

(defn collect-lip-sample-times [curves]
  (let [times (atom #{})]
    (doseq [[_key curve] curves
            frame curve]
      (when (and (finite-number? (:time frame)) (>= (:time frame) 0))
        (swap! times conj (rounded-sample-time (:time frame)))))
    (let [sorted (sort @times)]
      (doseq [[start end] (map vector sorted (rest sorted))
              :when (and (> end start) (<= (- end start) 0.12))]
        (swap! times conj (rounded-sample-time (/ (+ start end) 2)))))
    (->> @times sort vec)))

(defn trim-inactive-padding [curve]
  (let [frames (vec curve)
        first-active (first (keep-indexed (fn [idx frame]
                                            (when (> (:intensity frame) intensity-eps) idx))
                                          frames))]
    (if (nil? first-active)
      []
      (let [last-active (loop [idx (dec (count frames))]
                          (cond
                            (neg? idx) idx
                            (> (:intensity (get frames idx)) intensity-eps) idx
                            :else (recur (dec idx))))
            start (max 0 (dec first-active))
            end (min (dec (count frames)) (inc last-active))]
        (subvec frames start (inc end))))))

(defn limit-concurrent-lip-activation [curves]
  ;; Viseme slots 0-14 are mouth morphs. Never leave two of them lit, and never
  ;; let linear keyframe interpolation recreate a dual-morph blend between
  ;; samples: when the winner changes, insert an all-zero handoff sample first.
  (let [lip-keys (vec (keys curves))]
    (if (<= (count lip-keys) 1)
      curves
      (let [sample-times (collect-lip-sample-times curves)]
        (if (empty? sample-times)
          curves
          (let [zero-pose (into {} (map (fn [key] [key 0]) lip-keys))
                {:keys [frames]}
                (reduce (fn [{:keys [frames prev-winner]} time]
                          (let [values (mapv (fn [key]
                                               {:key key
                                                :visemeId (js/parseInt key 10)
                                                :value (state/clamp 0 lip-dominant-cap
                                                                    (sample-curve-at (get curves key) time))})
                                             lip-keys)
                                active (->> (into [] (filter #(> (:value %) intensity-eps)) values)
                                            (sort (fn [a b]
                                                    (let [score (fn [entry]
                                                                  (+ (:value entry)
                                                                     (if (= (:visemeId entry)
                                                                            (:B_M_P visemes/canonical-visemes))
                                                                       0.02
                                                                       0)))]
                                                      (compare (score b) (score a)))))
                                            vec)
                                winner (when (seq active) (:key (first active)))
                                pose (if winner
                                       (assoc zero-pose winner (:value (first active)))
                                       zero-pose)
                                handoff? (and prev-winner winner (not= prev-winner winner))
                                handoff-time (when handoff?
                                               (rounded-sample-time (max 0 (- time 0.001))))
                                frames-a (if handoff?
                                           (conj frames {:time handoff-time :pose zero-pose})
                                           frames)]
                            {:frames (conj frames-a {:time time :pose pose})
                             :prev-winner (or winner prev-winner)}))
                        {:frames [] :prev-winner nil}
                        sample-times)
                by-key (reduce (fn [result frame]
                                 (reduce (fn [acc key]
                                           (update acc key conj {:time (:time frame)
                                                                 :intensity (get (:pose frame) key 0)}))
                                         result
                                         lip-keys))
                               (into {} (map (fn [key] [key []]) lip-keys))
                               frames)]
            (into {} (map (fn [key]
                            [key (-> by-key
                                     (get key)
                                     deduplicate-curve
                                     trim-inactive-padding
                                     vec)])
                          lip-keys))))))))

(defn reduce-curve-keys [viseme-id curve]
  (let [frames (vec curve)]
    (if (<= (count frames) 3)
      frames
      (let [profile (envelope-profile viseme-id)
            reduced (loop [index 1
                           result [(first frames)]]
                      (if (>= index (dec (count frames)))
                        result
                        (let [prev (last result)
                              curr (get frames index)
                              next-frame (get frames (inc index))
                              preserves-peak? (>= (:intensity curr) (- (:peak profile) 0.02))
                              preserves-closure? (and (= viseme-id (:B_M_P visemes/canonical-visemes))
                                                      (>= (:intensity curr) 0.98))
                              near-flat? (and (< (js/Math.abs (- (:intensity curr) (:intensity prev))) 0.015)
                                              (< (js/Math.abs (- (:intensity next-frame) (:intensity curr))) 0.015))]
                          (recur (inc index)
                                 (if (or (not near-flat?) preserves-peak? preserves-closure?)
                                   (conj result curr)
                                   result)))))]
        (conj reduced (last frames))))))

(defn reduce-lip-keys [curves]
  (into {}
        (map (fn [[key curve]]
               (let [ordered (->> curve (sort-by :time) deduplicate-curve vec)]
                 [key (reduce-curve-keys (js/parseInt key 10) ordered)])))
        curves))

(defn build-raw-viseme-curves [events config]
  (reduce (fn [curves index]
            (let [event (get events index)
                  next-event (get events (inc index))
                  key (str (:visemeId event))
                  curve (build-event-viseme-curve event
                                                  next-event
                                                  (event-intensity event config))]
              (if (empty? curve)
                curves
                (update curves key #(if % (merge-curves % curve) curve)))))
          {}
          (range (count events))))

(defn build-lip-curves
  "Plan lip/viseme curves plus labiodental AU overlays. Pure; no jaw/tongue."
  [events config]
  (let [viseme-curves (-> (build-raw-viseme-curves events config)
                          (apply-coarticulation events)
                          limit-concurrent-lip-activation
                          reduce-lip-keys)
        labiodental-curves (build-labiodental-curves events config)]
    (merge viseme-curves labiodental-curves)))
