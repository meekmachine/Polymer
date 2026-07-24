(ns polymer.nlp-test
  (:require [cljs.test :refer [deftest is]]
            [polymer.nlp.double-metaphone :as metaphone]
            [polymer.nlp.phonemes :as phonemes]
            [polymer.lipsync.articulation.visemes :as visemes]))

;; Golden vectors for Double Metaphone parity (Philips / clj-fuzzy lookup,
;; full-length codes — not the classic 4-char key truncation).
(def golden-vectors
  [{:word "hello" :primary "HL" :secondary "HL"}
   {:word "world" :primary "ARLT" :secondary "FRLT"}
   {:word "the" :primary "0" :secondary "T"}
   {:word "think" :primary "0NK" :secondary "TNK"}
   {:word "ship" :primary "XP" :secondary "XP"}
   {:word "judge" :primary "JJ" :secondary "AJ"}
   {:word "michael" :primary "MKL" :secondary "MXL"}
   {:word "absolutely" :primary "APSLTL" :secondary "APSLTL"}
   {:word "crevalle" :primary "KRFL" :secondary "KRF"}
   {:word "Filipowitz" :primary "FLPTS" :secondary "FLPFX"}
   {:word "Xavier" :primary "SF" :secondary "SFR"}
   {:word "delicious" :primary "TLSS" :secondary "TLXS"}
   {:word "acceptingness" :primary "AKSPTNNS" :secondary "AKSPTNKNS"}
   {:word "allegrettos" :primary "ALKRTS" :secondary "AKRTS"}
   {:word "knight" :primary "NT" :secondary "NT"}
   {:word "chemistry" :primary "KMSTR" :secondary "KMSTR"}
   {:word "phone" :primary "FN" :secondary "FN"}
   {:word "quick" :primary "KK" :secondary "KK"}
   {:word "rhythm" :primary "R0M" :secondary "RTM"}
   {:word "a" :primary "A" :secondary "A"}
   {:word "I" :primary "A" :secondary "A"}
   {:word "to" :primary "T" :secondary "T"}
   {:word "of" :primary "AF" :secondary "AF"}
   {:word "and" :primary "ANT" :secondary "ANT"}
   {:word "not" :primary "NT" :secondary "NT"}
   {:word "what" :primary "AT" :secondary "AT"}
   {:word "five" :primary "FF" :secondary "FF"}
   {:word "pop" :primary "PP" :secondary "PP"}
   {:word "chess" :primary "XS" :secondary "XS"}
   {:word "duke" :primary "TK" :secondary "TK"}])

(deftest double-metaphone-matches-reference-golden-vectors
  (doseq [{:keys [word primary secondary]} golden-vectors]
    (let [codes (metaphone/double-metaphone word)]
      (is (= primary (:primary codes)) (str word " primary"))
      (is (= secondary (:secondary codes)) (str word " secondary")))))

(deftest word-phonemes-come-from-metaphone-code-chars
  ;; Codes with vowels / consonant-only recovery:
  ;; hello→HL + ortho → H E O L; the→0+E; ship→X I P; think→0 I N K
  (is (= ["H" "E" "O" "L"] (phonemes/word->phonemes "hello")))
  (is (= ["0" "E"] (phonemes/word->phonemes "the")))
  (is (= ["X" "I" "P"] (phonemes/word->phonemes "ship")))
  (is (= ["0" "I" "N" "K"] (phonemes/word->phonemes "think"))))

(deftest metaphone-vowel-pairs-collapse-to-diphthongs
  (is (= ["X" "OY" "S"] (phonemes/word->phonemes "choice")))
  (is (= ["S" "AY"] (phonemes/word->phonemes "say"))))

(deftest metaphone-phonemes-map-to-canonical-visemes
  (let [events (visemes/word->visemes "think")]
    (is (seq events))
    ;; 0 → Th viseme
    (is (some #(= "0" (:phoneme %)) events))
    (is (some #(= (:Th visemes/canonical-visemes) (:visemeId %)) events))))

(deftest text-phonemes-preserve-punctuation-pauses
  (let [phones (phonemes/text->phonemes "Hello, world!")]
    (is (some #{"PAUSE_COMMA"} phones))
    (is (some #{"PAUSE_EXCLAMATION"} phones))))

(deftest numeric-tokens-are-contentful-for-emphatic
  (let [analysis (phonemes/word->phonetic "42")]
    (is (true? (:numeric? analysis)))
    (is (= 2 (:codeLength analysis)))
    (is (= ["A" "A"] (:phonemes analysis)))))
