(ns polymer.vocal.snippet
  (:require [clojure.string :as str]
            [polymer.vocal.state :as state]
            [polymer.vocal.visemes :as visemes]))

;; Snippet construction is pure: it turns a normalized viseme timeline into the
;; animation data that Polymer Animation can schedule. It does not know about
;; React, audio playback, LiveKit, Azure credentials, or runtime handles.

(def jaw-au "26")
(def vocal-snippet-category "combined")
(def intensity-eps 0.001)
(def coarticulation-strength 0.52)
(def envelope-shoulder-ratio 0.55)
(def envelope-shoulder-intensity 0.62)
(def jaw-attack-sec 0.04)
(def jaw-release-sec 0.065)
(def jaw-transition-lead-sec 0.024)
(def jaw-long-gap-sec 0.09)

(def vowel-visemes
  #{(:AE visemes/canonical-visemes)
    (:Ah visemes/canonical-visemes)
    (:EE visemes/canonical-visemes)
    (:Er visemes/canonical-visemes)
    (:Ih visemes/canonical-visemes)
    (:Oh visemes/canonical-visemes)})

(defn finite-number? [value]
  (and (number? value) (js/isFinite value)))

(defn normalize-event [event]
  (let [viseme-id (or (:visemeId event) (:viseme_id event) (:id event))
        offset-ms (or (:offsetMs event) (:offset_ms event) (:offset event) 0)
        duration-ms (or (:durationMs event) (:duration_ms event) (:duration event) 0)]
    (when (and (finite-number? viseme-id)
               (finite-number? offset-ms)
               (finite-number? duration-ms)
               (pos? duration-ms))
      {:visemeId (int (state/clamp 0 14 viseme-id))
       :offsetMs (max 0 offset-ms)
       :durationMs (max 1 duration-ms)})))

(defn normalize-events [events]
  (->> (or events [])
       (map normalize-event)
       (remove nil?)
       (sort-by :offsetMs)
       vec))

(defn viseme-class [viseme-id]
  (cond
    (= viseme-id (:B_M_P visemes/canonical-visemes)) :bilabial
    (= viseme-id (:W_OO visemes/canonical-visemes)) :glide
    (contains? vowel-visemes viseme-id) :vowel
    (#{(:Ch_J visemes/canonical-visemes)
       (:F_V visemes/canonical-visemes)
       (:S_Z visemes/canonical-visemes)
       (:Th visemes/canonical-visemes)} viseme-id) :fricative
    (#{(:K_G_H_NG visemes/canonical-visemes)
       (:T_L_D_N visemes/canonical-visemes)} viseme-id) :tongue
    (= viseme-id (:R visemes/canonical-visemes)) :liquid
    :else :default))

(defn envelope-profile [viseme-id]
  ;; The profile keeps closure sounds crisp, vowels rounder, and fricatives
  ;; lighter. Those differences matter more than raw intensity for readable
  ;; speech animation.
  (cond
    (= viseme-id (:W_OO visemes/canonical-visemes)) {:attackSec 0.018 :releaseSec 0.026 :peak 0.98}
    (= viseme-id (:Oh visemes/canonical-visemes)) {:attackSec 0.020 :releaseSec 0.026 :peak 0.96}
    (= viseme-id (:EE visemes/canonical-visemes)) {:attackSec 0.016 :releaseSec 0.020 :peak 0.94}
    (= viseme-id (:Ih visemes/canonical-visemes)) {:attackSec 0.014 :releaseSec 0.018 :peak 0.88}
    (= viseme-id (:F_V visemes/canonical-visemes)) {:attackSec 0.010 :releaseSec 0.016 :peak 0.86}
    (= viseme-id (:Th visemes/canonical-visemes)) {:attackSec 0.010 :releaseSec 0.016 :peak 0.82}
    (= viseme-id (:Ch_J visemes/canonical-visemes)) {:attackSec 0.012 :releaseSec 0.018 :peak 0.84}
    (= viseme-id (:S_Z visemes/canonical-visemes)) {:attackSec 0.010 :releaseSec 0.014 :peak 0.78}
    (= viseme-id (:K_G_H_NG visemes/canonical-visemes)) {:attackSec 0.010 :releaseSec 0.014 :peak 0.68}
    (= viseme-id (:T_L_D_N visemes/canonical-visemes)) {:attackSec 0.012 :releaseSec 0.016 :peak 0.80}
    :else
    (case (viseme-class viseme-id)
      :bilabial {:attackSec 0.004 :releaseSec 0.006 :peak 1.0}
      :vowel {:attackSec 0.018 :releaseSec 0.022 :peak 0.92}
      :fricative {:attackSec 0.010 :releaseSec 0.014 :peak 0.72}
      :tongue {:attackSec 0.012 :releaseSec 0.016 :peak 0.76}
      :liquid {:attackSec 0.016 :releaseSec 0.018 :peak 0.82}
      :glide {:attackSec 0.012 :releaseSec 0.018 :peak 0.84}
      {:attackSec 0.010 :releaseSec 0.012 :peak 0.86})))

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
  (let [normalized (state/clamp 0 1 value)
        scale (max 0 (state/number-or intensity 1))]
    (cond
      (<= (js/Math.abs (- scale 1)) intensity-eps) normalized
      (<= scale 1) (* normalized scale)
      :else (- 1 (js/Math.pow (- 1 normalized) scale)))))

(defn scale-curve-intensity [curve intensity]
  (mapv (fn [frame]
          (assoc frame :intensity (scale-lip-intensity (:intensity frame) intensity)))
        curve))

(defn build-viseme-curve [viseme-id start-ms duration-ms intensity]
  (let [start-sec (/ start-ms 1000)
        duration-sec (/ duration-ms 1000)
        end-sec (+ start-sec duration-sec)
        profile (envelope-profile viseme-id)
        peak (:peak profile)]
    (if (<= duration-sec 0)
      []
      (let [attack-sec (min (:attackSec profile) (* duration-sec 0.45))
            release-sec (min (:releaseSec profile) (* duration-sec 0.45))]
        (scale-curve-intensity
         (if (<= duration-sec (+ attack-sec release-sec 0.002))
           [{:time start-sec :intensity 0}
            {:time (+ start-sec (* duration-sec 0.5)) :intensity peak}
            {:time end-sec :intensity 0}]
           (let [ramp-up-end (+ start-sec attack-sec)
                 hold-end (- end-sec release-sec)]
             (if (= (viseme-class viseme-id) :bilabial)
               [{:time start-sec :intensity 0}
                {:time ramp-up-end :intensity peak}
                {:time hold-end :intensity peak}
                {:time end-sec :intensity 0}]
               [{:time start-sec :intensity 0}
                {:time (+ start-sec (* attack-sec envelope-shoulder-ratio))
                 :intensity (* peak envelope-shoulder-intensity)}
                {:time ramp-up-end :intensity peak}
                {:time hold-end :intensity peak}
                {:time (+ hold-end (* release-sec (- 1 envelope-shoulder-ratio)))
                 :intensity (* peak envelope-shoulder-intensity)}
                {:time end-sec :intensity 0}])))
         intensity)))))

(defn merge-curves [existing incoming]
  (->> (concat existing incoming)
       (sort-by :time)
       deduplicate-curve
       vec))

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

(defn apply-coarticulation [curves events]
  ;; Carry and anticipation are conservative. Bilabials keep hard closures;
  ;; vowels/liquids/glides get a small overlap so provider timelines do not
  ;; look like disconnected mouth poses.
  (loop [index 0
         result curves]
    (if (>= index (dec (count events)))
      result
      (let [current (get events index)
            next-event (get events (inc index))
            current-class (viseme-class (:visemeId current))
            next-class (viseme-class (:visemeId next-event))
            current-end (+ (:offsetMs current) (:durationMs current))
            next-start (:offsetMs next-event)
            gap (- next-start current-end)
            current-key (str (:visemeId current))
            next-key (str (:visemeId next-event))
            blend? (and (< gap 50)
                        (> gap -30)
                        (not= current-class :bilabial)
                        (not= next-class :bilabial))
            carry? (contains? #{:vowel :liquid :glide} current-class)
            anticipate? (contains? #{:vowel :liquid :glide} next-class)]
        (recur (inc index)
               (cond-> result
                 (and blend? carry? (get result current-key))
                 (update current-key update-last-frame-time
                         #(min (max % (/ next-start 1000))
                               (+ % (* 0.010 coarticulation-strength))))

                 (and blend? anticipate? (get result next-key))
                 (update next-key update-first-frame-time
                         #(max 0 (- % (* 0.016 coarticulation-strength))))))))))

(defn push-jaw-frame [curve time intensity]
  (if (or (not (finite-number? time)) (not (finite-number? intensity)))
    curve
    (let [frame {:time (max 0 time)
                 :intensity (state/clamp 0 2 intensity)}
          previous (last curve)]
      (if (and previous (< (js/Math.abs (- (:time previous) (:time frame))) 0.001))
        (assoc-in curve [(dec (count curve)) :intensity] (:intensity frame))
        (conj curve frame)))))

(defn build-jaw-curve [events jaw-scale]
  (let [sorted-events (vec (sort-by :offsetMs events))]
    (loop [index 0
           curve []]
      (if (>= index (count sorted-events))
        (->> curve (sort-by :time) deduplicate-curve vec)
        (let [event (get sorted-events index)
              previous (get sorted-events (dec index))
              next-event (get sorted-events (inc index))
              start-sec (/ (:offsetMs event) 1000)
              duration-sec (/ (:durationMs event) 1000)
              end-sec (+ start-sec duration-sec)
              jaw-amount (min 2 (* (visemes/jaw-amount-for-viseme (:visemeId event)) jaw-scale))
              attack-sec (min jaw-attack-sec (max 0.006 (* duration-sec 0.35)))
              release-sec (min jaw-release-sec (max 0.010 (* duration-sec 0.45)))
              previous-end-sec (if previous (/ (+ (:offsetMs previous) (:durationMs previous)) 1000) 0)
              starts-after-gap? (or (nil? previous) (> (- start-sec previous-end-sec) jaw-long-gap-sec))
              curve-a (if starts-after-gap? (push-jaw-frame curve start-sec 0) curve)
              curve-b (push-jaw-frame curve-a (+ start-sec attack-sec) jaw-amount)]
          (recur (inc index)
                 (if next-event
                   (let [next-start-sec (/ (:offsetMs next-event) 1000)
                         gap-sec (- next-start-sec end-sec)]
                     (if (> gap-sec jaw-long-gap-sec)
                       (-> curve-b
                           (push-jaw-frame (max (+ start-sec attack-sec) (- end-sec release-sec)) jaw-amount)
                           (push-jaw-frame end-sec 0))
                       (push-jaw-frame curve-b
                                       (max (+ start-sec attack-sec) (- next-start-sec jaw-transition-lead-sec))
                                       jaw-amount)))
                   (-> curve-b
                       (push-jaw-frame (max (+ start-sec attack-sec) (- end-sec release-sec)) jaw-amount)
                       (push-jaw-frame end-sec 0)))))))))

(def snippet-counter (atom 0))

(defn next-snippet-name [prefix]
  (str prefix "_" (.now js/Date) "_" (swap! snippet-counter inc)))

(defn text-snippet-name [text]
  (let [words (->> (str/split (or text "") #"\s+")
                   (take 3)
                   (str/join "_")
                   str/lower-case
                   (#(str/replace % #"[^a-z_]" "")))]
    (next-snippet-name (str "vocal_" (if (pos? (count words)) words "timeline")))))

(defn build-curves [events config]
  (let [raw-curves (reduce (fn [curves event]
                             (let [key (str (:visemeId event))
                                   curve (build-viseme-curve (:visemeId event)
                                                             (:offsetMs event)
                                                             (:durationMs event)
                                                             (:intensity config))]
                               (if (empty? curve)
                                 curves
                                 (update curves key #(if % (merge-curves % curve) curve)))))
                           {}
                           events)
        articulated (apply-coarticulation raw-curves events)
        reduced (into {} (map (fn [[key curve]]
                                [key (->> curve (sort-by :time) deduplicate-curve vec)])
                              articulated))
        jaw-curve (build-jaw-curve events (:jawScale config))]
    (if (empty? jaw-curve)
      reduced
      (assoc reduced jaw-au jaw-curve))))

(defn max-snippet-time [events curves]
  (let [event-max (if (empty? events)
                    0
                    (apply max (map #(/ (+ (:offsetMs %) (:durationMs %)) 1000) events)))
        curve-max (if (empty? curves)
                    0
                    (apply max 0 (mapcat (fn [[_ curve]] (map :time curve)) curves)))]
    (max event-max curve-max)))

(defn build-vocal-snippet
  ([events config] (build-vocal-snippet events config nil))
  ([events config name]
   (let [config (state/sanitize-config config)
         normalized-events (normalize-events events)
         snippet-name (or name (next-snippet-name "vocal"))
         curves (build-curves normalized-events config)
         max-time (max-snippet-time normalized-events curves)]
     {:name snippet-name
      :snippetCategory vocal-snippet-category
      :snippetPriority (:priority config)
      ;; Provider/text timing is already baked into the keyframe offsets. Keep
      ;; playback at 1.0 so speech rate is not applied twice.
      :snippetPlaybackRate 1
      :snippetIntensityScale 1
      :snippetJawScale (:jawScale config)
      :autoVisemeJaw false
      :loop false
      :maxTime max-time
      :curves curves
      :metadata {:agency "vocal"
                 :visemeCount (count normalized-events)}})))

(defn build-text-snippet [text events config]
  (build-vocal-snippet events config (text-snippet-name text)))
