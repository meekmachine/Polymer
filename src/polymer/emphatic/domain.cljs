(ns polymer.emphatic.domain
  (:require [clojure.string :as str]))

;; Pure prosodic analysis ported from Latticework ProsodicAnalyzer.
;; Detects emphasis words, intonation, and gesture types without randomness.

(def function-words
  #{"the" "a" "an" "and" "or" "but" "in" "on" "at" "to" "for"
    "of" "with" "by" "from" "as" "is" "are" "was" "were" "be"
    "been" "have" "has" "had" "do" "does" "did" "will" "would"
    "can" "could" "should" "may" "might" "must" "i" "you" "he"
    "she" "it" "we" "they" "them" "their" "this" "that" "these"
    "those" "my" "your" "his" "her" "its" "our"})

(def content-word-patterns
  [#"^(what|where|when|who|why|how)$"
   #"^(not|never|no|nothing|nobody|nowhere)$"
   #"^(most|more|best|better|worst|worse|greatest|largest|smallest)$"
   #"^(must|should|could|would|might|may)$"
   #"^(very|really|quite|extremely|absolutely|definitely|certainly)$"
   #"^\d+$"])

(defn clean-word [word]
  (let [lower (.toLowerCase (str (or word "")))]
    (.replace lower (js/RegExp. "[^a-z0-9]" "g") "")))

(defn tokenize-words [text]
  (let [normalized (.replace (str (or text "")) (js/RegExp. "[.,!?;:]" "g") " ")
        parts (.split normalized (js/RegExp. "\\s+"))]
    (into []
          (comp (map clean-word)
                (remove #(zero? (count %))))
          (array-seq parts))))

(defn function-word? [word]
  (contains? function-words word))

(defn emphasis-word? [word idx word-count]
  (or (some #(re-matches % word) content-word-patterns)
      (and (> (count word) 7) (not (function-word? word)))
      (and (or (zero? idx) (= idx (dec word-count)))
           (not (function-word? word)))))

(defn detect-emphasis-indices [words]
  (let [word-count (count words)
        natural (into #{}
                      (keep (fn [idx]
                              (let [word (nth words idx nil)]
                                (when (and word (emphasis-word? word idx word-count))
                                  idx))))
                      (range word-count))]
    (if (< (count natural) (max 1 (quot word-count 5)))
      (into natural (for [i (range 2 word-count 4)] i))
      natural)))

(defn question? [text]
  (let [trimmed (str/trim (or text ""))]
    (or (str/ends-with? trimmed "?")
        (boolean (re-find #"^(what|where|when|who|why|how|is|are|do|does|did|can|could|would|should)"
                          trimmed)))))

(defn exclamation? [text]
  (str/ends-with? (str/trim (or text "")) "!"))

(defn detect-pause-indices [text _words]
  (let [tokens (array-seq (.split (str (or text "")) (js/RegExp. "\\s+")))]
    (loop [tokens tokens
           word-index 0
           pauses []]
      (if (empty? tokens)
        pauses
        (let [token (first tokens)
              cleaned (.replace (str token) (js/RegExp. "[.,!?;:]" "g") "")
              has-word? (pos? (count cleaned))
              pause? (boolean (re-find #"[.,;:]" token))]
          (recur (rest tokens)
                 (if has-word? (inc word-index) word-index)
                 (cond-> pauses
                   pause? (conj word-index))))))))

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
  (let [words (tokenize-words text)
        emphasis-indices (vec (sort (detect-emphasis-indices words)))
        question-intonation? (question? text)
        exclamation-emphasis? (exclamation? text)]
    {:text text
     :words words
     :emphasisWords emphasis-indices
     :questionIntonation question-intonation?
     :exclamationEmphasis exclamation-emphasis?
     :pausePositions (detect-pause-indices text words)
     :headGestures (generate-head-gestures emphasis-indices question-intonation? exclamation-emphasis?)
     :browGestures (generate-brow-gestures emphasis-indices question-intonation? exclamation-emphasis?)}))

(defn emphasis-index? [plan word-index]
  (contains? (set (:emphasisWords plan)) word-index))

(defn gestures-for-word-index [plan word-index]
  ;; Brow leads head so linguistic stress reads on the face first.
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
