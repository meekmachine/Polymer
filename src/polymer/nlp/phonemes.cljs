(ns polymer.nlp.phonemes
  (:require [clj-fuzzy.metaphone :as metaphone-single]
            [clj-fuzzy.porter :as porter]
            [polymer.nlp.double-metaphone :as metaphone]))

;; Standardized text → phoneme / lexical analysis for LipSync and Emphatic.
;;
;; LipSync pipeline:
;;   word → Double Metaphone → phoneme tokens → (vocalic / diphthong) → visemes
;;
;; Emphatic also consumes stem (Porter) and single Metaphone so stress can use
;; clj-fuzzy algorithms instead of English stop-word lists.

(def metaphone-vowels #{"A" "E" "I" "O" "U"})

(def diphthong-collapse
  ;; Rewrite rules on standardized metaphone vowel tokens only.
  [["O" "I" "OY"]
   ["A" "I" "AY"]
   ["A" "U" "AW"]
   ["O" "U" "OW"]
   ["E" "I" "EY"]
   ["E" "U" "UW"]])

(defn clean-word [word]
  (-> (or word "")
      str
      .toLowerCase
      (.replace (js/RegExp. "[^a-z]" "g") "")))

(defn numeric-token? [token]
  (boolean (re-matches #"[0-9]+" (str (or token "")))))

(defn pause-token [token]
  (case token
    "," "PAUSE_COMMA"
    ";" "PAUSE_SEMICOLON"
    ":" "PAUSE_COLON"
    "." "PAUSE_PERIOD"
    "?" "PAUSE_QUESTION"
    "!" "PAUSE_EXCLAMATION"
    "PAUSE_SPACE"))

(defn tokenize [text]
  (vec (re-seq #"[A-Za-z]+|[0-9]+|[,.;:!?]|\s+" (or text ""))))

(defn code->phonemes
  "Expand a Double Metaphone code string into standardized phoneme tokens."
  [code]
  (into []
        (map str)
        (seq (or code ""))))

(defn orthographic-vowel-phones
  "Map spelling vowels to metaphone vowel tokens when the code omitted them."
  [cleaned]
  (into []
        (keep (fn [ch]
                (case ch
                  \a "A"
                  \e "E"
                  \i "I"
                  \o "O"
                  \u "U"
                  \y "I"
                  nil)))
        (seq (or cleaned ""))))

(defn ensure-vocalic
  "Metaphone often drops non-initial vowels. LipSync still needs a vocalic
  nucleus, so splice a contiguous orthographic vowel block after the onset
  consonant when the code has none."
  [cleaned phonemes]
  (if (some metaphone-vowels phonemes)
    (vec phonemes)
    (let [vowels (orthographic-vowel-phones cleaned)
          nucleus (vec (take 2 vowels))]
      (cond
        (empty? nucleus) (vec phonemes)
        (empty? phonemes) nucleus
        :else (into [] (concat [(first phonemes)] nucleus (rest phonemes)))))))

(defn collapse-diphthongs
  "Collapse adjacent metaphone vowels into ARPABET-compatible diphthong tokens."
  [phonemes]
  (loop [remaining (vec phonemes)
         out []]
    (if (empty? remaining)
      out
      (let [a (first remaining)
            b (second remaining)
            match (when b
                    (some (fn [[x y diphthong]]
                            (when (and (= a x) (= b y)) diphthong))
                          diphthong-collapse))]
        (if match
          (recur (subvec remaining 2) (conj out match))
          (recur (subvec remaining 1) (conj out a)))))))

(defn word->phonemes
  "Extract standardized phonemes for a single word via Double Metaphone."
  [word]
  (cond
    (numeric-token? word)
    (vec (repeat (count (str word)) "A"))

    :else
    (let [cleaned (clean-word word)]
      (if (zero? (count cleaned))
        []
        (let [code (metaphone/primary-code cleaned)
              phones (-> code
                         code->phonemes
                         (->> (ensure-vocalic cleaned))
                         collapse-diphthongs)]
          (if (seq phones) phones ["A"]))))))

(defn word->phonetic
  "Rich phonetic + lexical analysis for one word (LipSync + Emphatic)."
  [word]
  (if (numeric-token? word)
    (let [digits (str word)]
      {:word word
       :cleaned digits
       :primary digits
       :secondary digits
       :metaphone digits
       :stem digits
       :stemLength (count digits)
       :phonemes (word->phonemes digits)
       :codeLength (count digits)
       :ambiguous? false
       :vowelLead? false
       :numeric? true})
    (let [cleaned (clean-word word)
          codes (when (pos? (count cleaned))
                  (metaphone/double-metaphone cleaned))
          primary (or (:primary codes) "")
          secondary (or (:secondary codes) "")
          stem (when (pos? (count cleaned))
                 (or (porter/stem cleaned) cleaned))
          single (when (pos? (count cleaned))
                   (or (metaphone-single/process cleaned) ""))
          phonemes (word->phonemes cleaned)]
      {:word word
       :cleaned cleaned
       :primary primary
       :secondary secondary
       :metaphone single
       :stem (or stem "")
       :stemLength (count (or stem ""))
       :phonemes phonemes
       :codeLength (count primary)
       :ambiguous? (and (pos? (count primary))
                        (not= primary secondary))
       :vowelLead? (boolean (re-matches #"[AEIOU].*" primary))
       :numeric? false})))

(defn text->phonemes [text]
  (into []
        (mapcat (fn [token]
                  (cond
                    (re-matches #"\s+" token) [(pause-token " ")]
                    (re-matches #"[,.;:!?]" token) [(pause-token token)]
                    :else (word->phonemes token))))
        (tokenize text)))

(defn text->word-analysis [text]
  (into []
        (keep (fn [token]
                (when (or (re-matches #"[A-Za-z]+" token)
                          (numeric-token? token))
                  (word->phonetic token))))
        (tokenize text)))
