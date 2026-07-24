(ns polymer.emphatic.domain
  (:require [clojure.string :as str]
            [clj-fuzzy.dice :as dice]
            [polymer.nlp.phonemes :as phonemes]))

;; Emphatic stress from clj-fuzzy / shared NLP signals — not stop-word lists.
;;
;; Signals:
;;   - Double Metaphone code length / ambiguity (phonetic mass)
;;   - Porter stem length (lexical/morphological mass)
;;   - Dice distinctiveness of metaphone codes vs other words in the utterance
;;   - Stem rarity within the utterance
;;   - Punctuation + first/last structure

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

(defn- safe-dice [a b]
  (let [left (str (or a ""))
        right (str (or b ""))]
    (if (or (zero? (count left)) (zero? (count right)))
      0.0
      (dice/coefficient left right))))

(defn phonetic-key [analysis]
  ;; Prefer full Double Metaphone primary; fall back to single Metaphone.
  (let [primary (or (:primary analysis) "")
        single (or (:metaphone analysis) "")]
    (if (pos? (count primary)) primary single)))

(defn mean-neighbor-dice
  "Mean Dice similarity of this word's phonetic key to every other word."
  [idx analyses]
  (let [key (phonetic-key (nth analyses idx))
        others (into []
                     (keep-indexed (fn [i analysis]
                                     (when (not= i idx)
                                       (phonetic-key analysis))))
                     analyses)]
    (if (empty? others)
      0.0
      (/ (reduce + 0 (map #(safe-dice key %) others))
         (count others)))))

(defn phonetic-distinctiveness [idx analyses]
  ;; Low overlap with other utterance codes ⇒ more likely content/stress.
  (max 0.0 (- 1.0 (mean-neighbor-dice idx analyses))))

(defn stem-rarity [idx analyses]
  (let [stem (or (:stem (nth analyses idx)) "")
        freq (count (filter #(= stem (or (:stem %) "")) analyses))]
    (if (pos? freq) (/ 1.0 freq) 0.0)))

(defn phonetic-weight
  "Higher weight ≈ more spoken / lexical mass."
  [analysis]
  (let [code-len (:codeLength analysis 0)
        stem-len (:stemLength analysis 0)
        ambiguous (if (:ambiguous? analysis) 0.35 0)
        vowel-lead (if (:vowelLead? analysis) 0.1 0)]
    (+ (* 0.45 code-len)
       (* 0.35 stem-len)
       ambiguous
       vowel-lead)))

(defn structural-bonus [idx word-count analysis]
  (cond-> 0
    (zero? idx) (+ 0.35)
    (= idx (dec word-count)) (+ 0.45)
    (:numeric? analysis) (+ 0.9)))

(defn emphasis-score [idx analyses]
  (let [analysis (nth analyses idx)
        word-count (count analyses)]
    (+ (phonetic-weight analysis)
       (* 1.4 (phonetic-distinctiveness idx analyses))
       (* 0.8 (stem-rarity idx analyses))
       (structural-bonus idx word-count analysis))))

(defn detect-emphasis-indices [analyses]
  (let [word-count (count analyses)]
    (if (zero? word-count)
      []
      (let [scored (mapv (fn [idx]
                           {:idx idx
                            :score (emphasis-score idx analyses)
                            :codeLength (:codeLength (nth analyses idx) 0)
                            :stemLength (:stemLength (nth analyses idx) 0)})
                         (range word-count))
            scores (mapv :score scored)
            median (nth (sort scores) (quot (count scores) 2))
            ;; Adaptive cut from the utterance itself — no stop list.
            threshold (max 1.25 (* 1.08 median))
            chosen (into #{}
                         (keep (fn [{:keys [idx score codeLength stemLength]}]
                                 (when (or (>= score threshold)
                                           (>= codeLength 4)
                                           (>= stemLength 5))
                                   idx)))
                         scored)]
        ;; If nothing clears the bar, keep only the single strongest word.
        (if (seq chosen)
          chosen
          #{(:idx (apply max-key :score scored))})))))

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
