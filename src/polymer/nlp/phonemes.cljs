(ns polymer.nlp.phonemes
  (:require [polymer.nlp.double-metaphone :as metaphone]))

;; Standardized text → phoneme extraction for LipSync and Emphatic.
;;
;; Pipeline:
;;   1. word → Double Metaphone primary code (standardized phonetic identity)
;;   2. code chars → phoneme tokens
;;   3. vocalic recovery when metaphone omits medial vowels (animation needs
;;      a nucleus; metaphone is a matching code, not a full pronunciation)
;;   4. adjacent metaphone vowels collapse to diphthong phonemes (OY/AY/…)
;;   5. LipSync maps phonemes → canonical visemes
;;
;; Emphatic consumes the same phonetic analysis (code length / ambiguity) so
;; stress planning is not a hardcoded English stop-word list.

(def metaphone-vowels #{"A" "E" "I" "O" "U"})

(def diphthong-collapse
  ;; Rewrite rules on standardized metaphone vowel tokens only — not grapheme
  ;; letter tables.
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
  consonant when the code has none. Keeping vowels contiguous preserves
  diphthong pairs (OI→OY, AI→AY) for the later collapse step."
  [cleaned phonemes]
  (if (some metaphone-vowels phonemes)
    (vec phonemes)
    (let [vowels (orthographic-vowel-phones cleaned)
          ;; Two-vowel nuclei cover English diphthong spellings; extra trailing
          ;; silent/schwa letters (choice→oie) are ignored for mouth planning.
          nucleus (vec (take 2 vowels))]
      (cond
        (empty? nucleus) (vec phonemes)
        (empty? phonemes) nucleus
        :else (into [] (concat [(first phonemes)] nucleus (rest phonemes)))))))

(defn collapse-diphthongs
  "Collapse adjacent metaphone vowels into ARPABET-compatible diphthong tokens
  that the existing viseme diphthong planner already understands."
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
    ;; Digits are spoken content for Emphatic; LipSync can treat each digit as
    ;; a short open-mouth beat until provider timing exists.
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
  "Rich phonetic analysis for one word (shared by LipSync + Emphatic)."
  [word]
  (if (numeric-token? word)
    (let [digits (str word)]
      {:word word
       :cleaned digits
       :primary digits
       :secondary digits
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
          phonemes (word->phonemes cleaned)]
      {:word word
       :cleaned cleaned
       :primary primary
       :secondary secondary
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
