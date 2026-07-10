(ns polymer.lipsync.articulation.snippet
  (:require [clojure.string :as str]
            [polymer.lipsync.articulation.jaw :as jaw]
            [polymer.lipsync.state :as state]
            [polymer.lipsync.articulation.tongue :as tongue]
            [polymer.lipsync.articulation.visemes :as visemes]))

;; Snippet construction is pure: it turns a normalized viseme timeline into the
;; animation data that Polymer Animation can schedule. It does not know about
;; host UI code, audio playback, LiveKit, Azure credentials, or engine handles.

(def jaw-au "103")
(def labiodental-contact-au "32")
(def labiodental-press-au "24")
(def intensity-eps 0.001)
(def coarticulation-strength 0.52)
(def envelope-shoulder-ratio 0.55)
(def envelope-shoulder-intensity 0.62)
(def lip-total-activation-cap 1.05)
(def lip-dominant-cap 1)
(def lip-secondary-ratio 0.30)
(def lip-secondary-cap 0.22)
(def closure-dominance-threshold 0.55)
(def closure-secondary-cap 0.035)
(def plosive-preclose-sec 0.028)
(def plosive-hold-sec 0.022)
(def plosive-release-sec 0.018)
(def labiodental-contact-peak 0.24)
(def labiodental-press-peak 0.12)

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
        duration-ms (or (:durationMs event) (:duration_ms event) (:duration event) 0)
        jaw-activation (or (:jawActivation event)
                           (:jaw_activation event)
                           (:jaw event)
                           (:jawAmount event))]
    (when (and (finite-number? viseme-id)
               (finite-number? offset-ms)
               (finite-number? duration-ms)
               (pos? duration-ms))
      (let [canonical-id (int (state/clamp 0 14 viseme-id))]
        (cond->
         {:visemeId canonical-id
          :offsetMs (max 0 offset-ms)
          :durationMs (max 1 duration-ms)
          :jawActivation (if (finite-number? jaw-activation)
                           (state/clamp 0 2 jaw-activation)
                           (visemes/jaw-activation-for-viseme canonical-id))}
          (:phoneme event) (assoc :phoneme (:phoneme event))
          (or (:phonemeClass event) (:phoneme_class event))
          (assoc :phonemeClass (or (:phonemeClass event) (:phoneme_class event)))
          (or (:phonemeClasses event) (:phoneme_classes event))
          (assoc :phonemeClasses (or (:phonemeClasses event) (:phoneme_classes event))))))))

(defn normalize-events [events]
  ;; Normalize/drop is element-local and transducer-friendly. Sorting is kept as
  ;; the next explicit step because ordering is a whole-collection decision.
  (->> (into [] (keep normalize-event) (or events []))
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

(defn vowel-event? [event]
  (or (has-class? event "vowel")
      (contains? vowel-visemes (:visemeId event))))

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

(defn build-plosive-closure-curve [event next-event intensity]
  ;; P/B need a visible close-hold-release phrase, not just a generic envelope.
  ;; The close starts slightly early, holds the B_M_P target briefly, then drops
  ;; sharply at the following vowel boundary when one is present. Jaw motion is
  ;; still separate and comes only from the jaw planner.
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
        peak (:peak (envelope-profile (:visemeId event)))]
    (scale-curve-intensity
     (-> [{:time close-start-sec :intensity 0}
          {:time close-end-sec :intensity peak}
          {:time hold-end-sec :intensity peak}
          {:time release-end-sec :intensity 0}]
         deduplicate-curve
         vec)
     intensity)))

(defn build-event-viseme-curve [event next-event intensity]
  (if (bilabial-plosive? event)
    (build-plosive-closure-curve event next-event intensity)
    (build-viseme-curve (:visemeId event)
                        (:offsetMs event)
                        (:durationMs event)
                        intensity)))

(defn merge-curves [existing incoming]
  (->> (concat existing incoming)
       (sort-by :time)
       deduplicate-curve
       vec))

(defn build-labiodental-au-curve [event peak intensity]
  ;; F/V already activate the F_V viseme. This small AU overlay gives the lower
  ;; lip a top-teeth contact cue on rigs that expose AU32/AU24, while rigs that
  ;; do not expose those AUs can ignore the channels safely.
  (let [start-sec (/ (:offsetMs event) 1000)
        duration-sec (/ (:durationMs event) 1000)
        end-sec (+ start-sec duration-sec)
        attack-sec (min 0.012 (* duration-sec 0.35))
        release-sec (min 0.016 (* duration-sec 0.35))
        hold-end-sec (max (+ start-sec attack-sec)
                          (- end-sec release-sec))]
    (scale-curve-intensity
     [{:time start-sec :intensity 0}
      {:time (+ start-sec attack-sec) :intensity peak}
      {:time hold-end-sec :intensity peak}
      {:time end-sec :intensity 0}]
     intensity)))

(defn build-labiodental-curves [events intensity]
  (reduce (fn [curves event]
            (if (labiodental-event? event)
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
                                                                     intensity))))
              curves))
          {}
          events))

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

(defn sample-curve-at [curve time]
  ;; The limiter below works on sampled poses instead of raw keyframes. That is
  ;; what keeps dense Azure timelines from briefly activating several full lip
  ;; shapes at once between keyframes.
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
    (doseq [[key curve] curves
            :when (not= key jaw-au)
            frame curve]
      (when (and (finite-number? (:time frame)) (>= (:time frame) 0))
        (swap! times conj (rounded-sample-time (:time frame)))))
    (let [sorted (sort @times)]
      ;; Add midpoints across short spans so overlap is capped between envelope
      ;; keyframes, where the visual flapping usually appears.
      (doseq [[start end] (map vector sorted (rest sorted))
              :when (and (> end start) (<= (- end start) 0.12))]
        (swap! times conj (rounded-sample-time (/ (+ start end) 2)))))
    (->> @times sort vec)))

(defn fit-secondary-activation [active adjusted budget]
  (let [secondary (vec (rest active))
        secondary-sum (transduce (map :value) + 0 secondary)]
    (if (or (empty? secondary) (<= secondary-sum budget))
      adjusted
      (let [scale (/ budget secondary-sum)]
        (reduce (fn [result entry]
                  (assoc result (:key entry) (* (:value entry) scale)))
                adjusted
                secondary)))))

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
  ;; Stable Latticework intentionally does not let every overlapping viseme keep
  ;; its full value. The strongest active shape leads, secondary shapes get a
  ;; small budget, and closed-mouth bilabials suppress everything else.
  (let [lip-keys (into [] (remove #(= % jaw-au)) (keys curves))]
    (if (<= (count lip-keys) 1)
      curves
      (let [sample-times (collect-lip-sample-times curves)]
        (if (empty? sample-times)
          curves
          (let [normalized (reduce (fn [result time]
                                     (let [values (mapv (fn [key]
                                                          {:key key
                                                           :visemeId (js/parseInt key 10)
                                                           :value (state/clamp 0 lip-dominant-cap
                                                                               (sample-curve-at (get curves key) time))})
                                                        lip-keys)
                                           active (->> (into [] (filter #(> (:value %) intensity-eps)) values)
                                                       (sort-by :value >)
                                                       vec)
                                           adjusted-a (into {} (map (fn [entry] [(:key entry) (:value entry)]) values))
                                           adjusted-b (if (> (count active) 1)
                                                        (let [dominant (first active)]
                                                          (if (and (= (:visemeId dominant) (:B_M_P visemes/canonical-visemes))
                                                                   (>= (:value dominant) closure-dominance-threshold))
                                                            (fit-secondary-activation active adjusted-a closure-secondary-cap)
                                                            (let [total (transduce (map :value) + 0 active)]
                                                              (if (> total lip-total-activation-cap)
                                                                (let [budget (max 0
                                                                                  (min (- lip-total-activation-cap (:value dominant))
                                                                                       (* (:value dominant) lip-secondary-ratio)
                                                                                       lip-secondary-cap))]
                                                                  (fit-secondary-activation active adjusted-a budget))
                                                                adjusted-a))))
                                                        adjusted-a)]
                                       (reduce (fn [acc key]
                                                 (update acc key conj {:time time
                                                                       :intensity (get adjusted-b key 0)}))
                                               result
                                               lip-keys)))
                                   (into {} (map (fn [key] [key []]) lip-keys))
                                   sample-times)]
            (into {} (map (fn [key]
                            [key (-> normalized
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
                 [key (if (= key jaw-au)
                        ordered
                        (reduce-curve-keys (js/parseInt key 10) ordered))])))
        curves))

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

(defn build-curves [events config]
  (let [raw-curves (reduce (fn [curves index]
                             (let [event (get events index)
                                   next-event (get events (inc index))
                                   key (str (:visemeId event))
                                   curve (build-event-viseme-curve event
                                                                   next-event
                                                                   (:intensity config))]
                               (if (empty? curve)
                                 curves
                                 (update curves key #(if % (merge-curves % curve) curve)))))
                           {}
                           (range (count events)))
        articulated (-> raw-curves
                        (apply-coarticulation events)
                        limit-concurrent-lip-activation
                        reduce-lip-keys)
        jaw-curve (build-jaw-curve events (:jawScale config))
        tongue-curves (tongue/build-tongue-curves events (:tongueScale config))
        labiodental-curves (build-labiodental-curves events (:intensity config))
        with-jaw (if (empty? jaw-curve)
                   articulated
                   (assoc articulated jaw-au jaw-curve))]
    ;; Articulator AU curves are added after lip limiting because they are
    ;; ordinary AU controls, not viseme slots competing for lip-shape budget.
    (merge with-jaw labiodental-curves tongue-curves)))

(defn lipsync-channel-target [curve-key]
  ;; LipSync owns its animation namespace explicitly. Numeric keys 0-14 are
  ;; canonical viseme slots. Jaw motion is emitted as Embody's lip-sync control
  ;; 103, not as a FACS AU. That keeps Embody in charge of bone actuation while
  ;; avoiding AU26, which also drives the CC4 Jaw_Open morph and can cover the
  ;; separate viseme mouth shapes.
  ;; Numeric keys above 14 that are not the jaw curve remain regular AUs for
  ;; tongue/labiodental overlays.
  (let [key (str curve-key)
        numeric-id (when (re-matches #"^\d+$" key)
                     (js/parseInt key 10))]
    (cond
      (= key jaw-au)
      {:type "lipSync" :id 103}

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
         max-time (max-snippet-time normalized-events curves)]
     {:name snippet-name
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
      :channels (curves->channels curves)
      :metadata {:agency "lipSync"
                 :visemeCount (count normalized-events)
                 :tongueCurveCount (count (select-keys curves tongue/tongue-au-keys))}})))

(defn build-text-snippet [text events config]
  (build-lipsync-snippet events config (text-snippet-name text)))
