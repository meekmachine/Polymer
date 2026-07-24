(ns polymer.emphatic.domain
  (:require [clojure.string :as str]
            [polymer.nlp.phonemes :as phonemes]))

;; Emphatic analysis uses standardized NLP phonetics (Double Metaphone), not a
;; hardcoded stop-word list. Contentfulness comes from phonetic code weight,
;; ambiguity, punctuation, and sentence structure.

(defn clean-word [word]
  (phonemes/clean-word word))

(defn tokenize-words [text]
  (->> (phonemes/tokenize text)
       (filter #(or (re-matches #"[A-Za-z]+" %)
                    (phonemes/numeric-token? %)))
       (mapv (fn [token]
               (if (phonemes/numeric-token? token)
                 (str token)
                 (clean-word token))))
       (filterv #(pos? (count %)))))

(defn question? [text]
  ;; Intonation cue from punctuation only — no English question-word list.
  (str/ends-with? (str/trim (or text "")) "?"))

(defn exclamation? [text]
  (str/ends-with? (str/trim (or text "")) "!"))

(defn detect-pause-indices [text _words]
  (let [tokens (phonemes/tokenize text)]
    (loop [tokens tokens
           word-index 0
           pauses []]
      (if (empty? tokens)
        pauses
        (let [token (first tokens)
              word? (or (boolean (re-matches #"[A-Za-z]+" token))
                        (phonemes/numeric-token? token))
              pause? (boolean (re-matches #"[,.;:]" token))]
          (recur (rest tokens)
                 (if word? (inc word-index) word-index)
                 (cond-> pauses
                   (and pause? (pos? word-index)) (conj (dec word-index)))))))))

(defn phonetic-weight
  "Higher weight ≈ more spoken mass / more likely to carry stress."
  [analysis]
  (let [code-len (:codeLength analysis)
        ambiguous (if (:ambiguous? analysis) 0.35 0)
        vowel-lead (if (:vowelLead? analysis) 0.15 0)
        ;; Very short codes (0/A/T/…) are typically light function material.
        floor (if (<= code-len 1) 0.15 0)]
    (+ (* 0.55 code-len) ambiguous vowel-lead (- floor))))

(defn structural-bonus [idx word-count analysis]
  (cond-> 0
    (zero? idx) (+ 0.45)
    (= idx (dec word-count)) (+ 0.55)
    ;; Digits are spoken content and usually carry stress.
    (:numeric? analysis) (+ 0.8)))

(defn emphasis-score [idx word-count analysis]
  (+ (phonetic-weight analysis)
     (structural-bonus idx word-count analysis)))

(defn detect-emphasis-indices [analyses]
  (let [word-count (count analyses)
        scored (map-indexed (fn [idx analysis]
                              {:idx idx
                               :score (emphasis-score idx word-count analysis)
                               :codeLength (:codeLength analysis)})
                            analyses)
        ;; Adaptive threshold from median score so we do not need stop lists.
        scores (mapv :score scored)
        median (if (seq scores)
                 (nth (sort scores) (quot (count scores) 2))
                 0)
        threshold (max 1.1 (* 1.05 median))
        natural (into #{}
                      (keep (fn [{:keys [idx score codeLength]}]
                              (when (or (>= score threshold)
                                        (>= codeLength 4))
                                idx)))
                      scored)]
    (if (< (count natural) (max 1 (quot word-count 5)))
      (into natural (for [i (range 2 word-count 4)] i))
      natural)))

(defn select-head-gesture-type [idx question?]
  (cond
    (and question? (zero? idx)) "tilt"
    (even? idx) "nod"
    (zero? (mod idx 3)) "turn"
    :else "tilt"))

(defn select-brow-gesture-type [idx question? exclamation?]
  (cond
    question? "raise"
    exclamation? "flash"
    (zero? (mod idx 3)) "furrow"
    :else "raise"))

(defn head-gesture-for-index [idx question? intensity]
  {:wordIndex idx
   :type (select-head-gesture-type idx question?)
   :intensity intensity
   :duration 0.75})

(defn brow-gesture-for-index [idx question? exclamation? intensity]
  {:wordIndex idx
   :type (select-brow-gesture-type idx question? exclamation?)
   :intensity intensity
   :duration 0.7})

(defn generate-head-gestures [emphasis-indices question? exclamation?]
  (mapv (fn [idx]
          (head-gesture-for-index idx question?
                                  (if exclamation? 0.85 0.7)))
        emphasis-indices))

(defn generate-brow-gestures [emphasis-indices question? exclamation?]
  (mapv (fn [idx]
          (brow-gesture-for-index idx question? exclamation?
                                  (if exclamation? 0.85 0.65)))
        emphasis-indices))

(defn analyze-utterance [text]
  (let [analyses (phonemes/text->word-analysis text)
        words (mapv :cleaned analyses)
        emphasis-indices (vec (sort (detect-emphasis-indices analyses)))
        emphasis-index-set (set emphasis-indices)
        question-intonation? (question? text)
        exclamation-emphasis? (exclamation? text)]
    {:text text
     :words words
     :analyses analyses
     :emphasisWords emphasis-indices
     :emphasisWordSet emphasis-index-set
     :questionIntonation question-intonation?
     :exclamationEmphasis exclamation-emphasis?
     :pausePositions (detect-pause-indices text words)
     :headGestures (generate-head-gestures emphasis-indices question-intonation? exclamation-emphasis?)
     :browGestures (generate-brow-gestures emphasis-indices question-intonation? exclamation-emphasis?)}))

(defn emphasis-index? [plan word-index]
  (contains? (or (:emphasisWordSet plan) (set (:emphasisWords plan))) word-index))

(defn gestures-for-word-index [plan word-index]
  (vec (concat
        (keep #(when (= word-index (:wordIndex %)) %) (:browGestures plan))
        (keep #(when (= word-index (:wordIndex %)) %) (:headGestures plan)))))

(defn gesture-for-word-index [plan word-index]
  (first (gestures-for-word-index plan word-index)))

(defn gesture-channel [gesture]
  (case (:type gesture)
    ("nod" "tilt" "turn") :head
    ("raise" "furrow" "flash") :brow
    :brow))
