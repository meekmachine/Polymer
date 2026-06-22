(ns polymer.vocal.visemes
  (:require [clojure.string :as str]
            [polymer.vocal.state :as state]))

;; Polymer keeps the canonical 15-slot CC4/ARKit viseme order beside the CLJS
;; lip-sync code so numeric curves line up with Embody/Loom3. This mirrors the
;; installed Embody export:
;; ["AE" "Ah" "B_M_P" "Ch_J" "EE" "Er" "F_V" "Ih" "K_G_H_NG" "Oh" "R"
;;  "S_Z" "T_L_D_N" "Th" "W_OO"].

(def canonical-visemes
  {:AE 0
   :Ah 1
   :B_M_P 2
   :Ch_J 3
   :EE 4
   :Er 5
   :F_V 6
   :Ih 7
   :K_G_H_NG 8
   :Oh 9
   :R 10
   :S_Z 11
   :T_L_D_N 12
   :Th 13
   :W_OO 14})

(def jaw-activations
  ;; JALI treats visible speech as two mostly independent axes: lip action and
  ;; jaw action. These values are not baked into the viseme morphs. They are
  ;; only the default jaw-axis target used when an input provider does not send
  ;; a more specific jawActivation value for a phoneme/viseme event. They are
  ;; intentionally lower than Embody's manual viseme jaw amounts: speech jaw is
  ;; an animation performance parameter, not a full pose slider.
  [0.48 0.55 0 0.22 0.14 0.24 0.06 0.14 0.24 0.40 0.24 0.06 0.22 0.10 0.34])

(defn jaw-activation-for-viseme [viseme-id]
  (get jaw-activations viseme-id 0.3))

(declare normalize-phoneme)

(defn jaw-activation-for-phoneme [phoneme viseme-id]
  ;; The lip target and the jaw target are allowed to disagree. For example,
  ;; HH borrows the Ah lip family but should not open the jaw like a strong Ah
  ;; vowel. This keeps Web Speech fallback from flapping the jaw on consonants.
  (let [normalized (normalize-phoneme phoneme)]
    (cond
      (contains? #{"P" "B" "M"} normalized) 0
      (contains? #{"F" "V" "S" "Z" "SH" "ZH" "TH" "DH"} normalized) 0.06
      (contains? #{"T" "D" "N" "L" "K" "G" "NG" "CH" "JH"} normalized) 0.16
      (contains? #{"H" "HH" "Y" "W" "R"} normalized) 0.18
      :else (jaw-activation-for-viseme viseme-id))))

(def phoneme->viseme
  {"sil" (:B_M_P canonical-visemes)
   "pau" (:B_M_P canonical-visemes)
   "PAUSE" (:B_M_P canonical-visemes)
   "A" (:Ah canonical-visemes)
   "E" (:EE canonical-visemes)
   "I" (:Ih canonical-visemes)
   "O" (:Oh canonical-visemes)
   "U" (:W_OO canonical-visemes)
   "0" (:Th canonical-visemes)
   "X" (:S_Z canonical-visemes)
   "J" (:Ch_J canonical-visemes)
   "AE" (:AE canonical-visemes)
   "AX" (:Ah canonical-visemes)
   "AH" (:Ah canonical-visemes)
   "AA" (:Ah canonical-visemes)
   "AO" (:Oh canonical-visemes)
   "EY" (:EE canonical-visemes)
   "EH" (:EE canonical-visemes)
   "UH" (:W_OO canonical-visemes)
   "ER" (:Er canonical-visemes)
   "Y" (:Ih canonical-visemes)
   "IY" (:EE canonical-visemes)
   "IH" (:Ih canonical-visemes)
   "IX" (:Ih canonical-visemes)
   "W" (:W_OO canonical-visemes)
   "UW" (:W_OO canonical-visemes)
   "OW" (:Oh canonical-visemes)
   "AW" (:Ah canonical-visemes)
   "OY" (:Oh canonical-visemes)
   "AY" (:Ah canonical-visemes)
   "H" (:Ah canonical-visemes)
   "HH" (:Ah canonical-visemes)
   "R" (:R canonical-visemes)
   "L" (:T_L_D_N canonical-visemes)
   "S" (:S_Z canonical-visemes)
   "Z" (:S_Z canonical-visemes)
   "SH" (:S_Z canonical-visemes)
   "CH" (:Ch_J canonical-visemes)
   "JH" (:Ch_J canonical-visemes)
   "ZH" (:S_Z canonical-visemes)
   "TH" (:Th canonical-visemes)
   "DH" (:Th canonical-visemes)
   "F" (:F_V canonical-visemes)
   "V" (:F_V canonical-visemes)
   "D" (:T_L_D_N canonical-visemes)
   "T" (:T_L_D_N canonical-visemes)
   "N" (:T_L_D_N canonical-visemes)
   "K" (:K_G_H_NG canonical-visemes)
   "G" (:K_G_H_NG canonical-visemes)
   "NG" (:K_G_H_NG canonical-visemes)
   "P" (:B_M_P canonical-visemes)
   "B" (:B_M_P canonical-visemes)
   "M" (:B_M_P canonical-visemes)})

(def phoneme-durations
  {"A" 50 "E" 45 "I" 40 "O" 55 "U" 50
   "0" 35 "X" 45 "J" 40
   "P" 25 "B" 25 "T" 20 "D" 20 "K" 30 "G" 30
   "F" 35 "V" 35 "S" 40 "Z" 40 "SH" 45 "ZH" 45
   "TH" 35 "DH" 35 "H" 30 "HH" 30
   "CH" 40 "JH" 40
   "M" 35 "N" 35 "NG" 40
   "L" 40 "R" 40
   "W" 35 "Y" 30
   "IY" 50 "EY" 60 "UW" 50 "OW" 60 "AO" 55
   "IH" 40 "EH" 45 "UH" 45 "AH" 45 "AX" 35
   "AY" 65 "AW" 65 "OY" 70
   "ER" 50 "AA" 55 "AE" 55
   "IX" 30})

(def fallback-text-duration-scale 2)

(def pause-durations
  {"PAUSE_SPACE" 0
   "PAUSE_COMMA" 50
   "PAUSE_PERIOD" 100
   "PAUSE_QUESTION" 100
   "PAUSE_EXCLAMATION" 100
   "PAUSE_SEMICOLON" 75
   "PAUSE_COLON" 75})

(def vowels
  #{"A" "E" "I" "O" "U"
    "AA" "AE" "AH" "AO" "AW" "AX" "AY"
    "EH" "ER" "EY"
    "IH" "IX" "IY"
    "OW" "OY"
    "UH" "UW"})

(def prefix-phones
  [["th" ["TH"]] ["sh" ["SH"]] ["ch" ["CH"]] ["wh" ["W"]] ["ph" ["F"]]
   ["gh" ["G"]] ["ng" ["NG"]] ["ck" ["K"]] ["qu" ["K" "W"]]
   ["oo" ["UW"]] ["ee" ["IY"]] ["ea" ["IY"]] ["ai" ["EY"]] ["ay" ["EY"]]
   ["oa" ["OW"]] ["ou" ["AW"]] ["ow" ["OW"]] ["oi" ["OY"]] ["oy" ["OY"]]
   ["au" ["AO"]] ["aw" ["AO"]] ["ie" ["IY"]] ["ei" ["EY"]] ["ue" ["UW"]]
   ["ui" ["UW"]]])

(defn starts-with? [value prefix]
  (str/starts-with? value prefix))

(defn prefix-match [remaining]
  (some (fn [[prefix phones]]
          (when (starts-with? remaining prefix)
            {:size (count prefix) :phones phones}))
        prefix-phones))

(defn single-letter-phones [remaining]
  ;; This is intentionally deterministic and lightweight, not a dictionary. Its
  ;; role is fallback text-to-viseme planning when provider timing is absent.
  (let [letter (subs remaining 0 1)
        next-letter (when (> (count remaining) 1) (subs remaining 1 2))
        at-end? (= 1 (count remaining))]
    (case letter
      "a" ["AE"]
      "e" ["EH"]
      "i" ["IH"]
      "o" ["AA"]
      "u" ["AH"]
      "y" (if at-end? ["IY"] ["Y"])
      "b" ["B"]
      "c" (if (#{"e" "i"} next-letter) ["S"] ["K"])
      "d" ["D"]
      "f" ["F"]
      "g" (if (#{"e" "i"} next-letter) ["JH"] ["G"])
      "h" ["HH"]
      "j" ["JH"]
      "k" ["K"]
      "l" ["L"]
      "m" ["M"]
      "n" ["N"]
      "p" ["P"]
      "r" ["R"]
      "s" ["S"]
      "t" ["T"]
      "v" ["V"]
      "w" ["W"]
      "x" ["K" "S"]
      "z" ["Z"]
      [])))

(defn word->phonemes [word]
  (loop [remaining (-> (or word "") str/lower-case (str/replace #"[^a-z]" ""))
         phonemes []]
    (if (zero? (count remaining))
      phonemes
      (if-let [match (prefix-match remaining)]
        (recur (subs remaining (:size match)) (into phonemes (:phones match)))
        (recur (subs remaining 1) (into phonemes (single-letter-phones remaining)))))))

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
  (re-seq #"[A-Za-z]+|[,.;:!?]|\s+" (or text "")))

(defn text->phonemes [text]
  (->> (tokenize text)
       (mapcat (fn [token]
                 (if (or (re-matches #"\s+" token)
                         (re-matches #"[,.;:!?]" token))
                   [(pause-token token)]
                   (word->phonemes token))))
       vec))

(defn normalize-phoneme [phoneme]
  (-> (or phoneme "") str/upper-case (str/replace #"[0-9]" "")))

(defn vowel? [phoneme]
  (contains? vowels (normalize-phoneme phoneme)))

(defn phoneme->viseme-duration [phoneme]
  (if (str/starts-with? (or phoneme "") "PAUSE_")
    {:phoneme phoneme
     :viseme (:B_M_P canonical-visemes)
     :jawActivation 0
     :duration (get pause-durations phoneme 300)}
    (let [normalized (normalize-phoneme phoneme)
          viseme-id (get phoneme->viseme normalized (:B_M_P canonical-visemes))
          fallback-duration (if (vowel? normalized) 50 35)]
      {:phoneme normalized
       :viseme viseme-id
       :jawActivation (jaw-activation-for-phoneme normalized viseme-id)
       :duration (get phoneme-durations normalized fallback-duration)})))

(defn adjust-duration [duration speech-rate]
  (js/Math.round (/ (* duration fallback-text-duration-scale)
                    (state/clamp 0.2 3 (state/number-or speech-rate 1)))))

(defn phonemes->visemes
  ([phonemes] (phonemes->visemes phonemes 0 1))
  ([phonemes start-ms speech-rate]
   (loop [remaining phonemes
          current-ms start-ms
          events []]
     (if (empty? remaining)
       events
       (let [{:keys [phoneme viseme jawActivation duration]} (phoneme->viseme-duration (first remaining))
             scaled-duration (adjust-duration duration speech-rate)]
         (if (<= scaled-duration 0)
           (recur (rest remaining) current-ms events)
           (recur (rest remaining)
                  (+ current-ms scaled-duration)
                  (conj events {:visemeId viseme
                                :phoneme phoneme
                                :jawActivation jawActivation
                                :offsetMs current-ms
                                :durationMs scaled-duration}))))))))

(defn word->visemes
  ([word] (word->visemes word 0 1))
  ([word start-ms speech-rate]
   (phonemes->visemes (word->phonemes word) start-ms speech-rate)))

(defn text->visemes
  ([text] (text->visemes text 1))
  ([text speech-rate]
   (phonemes->visemes (text->phonemes text) 0 speech-rate)))

(defn phonemes-duration-ms [phonemes speech-rate]
  (reduce
   +
   0
   (map (fn [phoneme]
          (adjust-duration (:duration (phoneme->viseme-duration phoneme)) speech-rate))
        phonemes)))

(defn text->word-timings
  "Estimate word timing from the same deterministic text planner used for
  fallback Web Speech visemes. Provider timings are still preferred, but Web
  Speech only gives boundary callbacks while speaking; this gives those
  callbacks an expected timeline for drift correction."
  ([text] (text->word-timings text 1))
  ([text speech-rate]
   (loop [tokens (tokenize text)
          current-ms 0
          timings []]
     (if (empty? tokens)
       timings
       (let [token (first tokens)]
         (cond
           (re-matches #"[A-Za-z]+" token)
           (let [phonemes (word->phonemes token)
                 duration-ms (phonemes-duration-ms phonemes speech-rate)
                 end-ms (+ current-ms duration-ms)]
             (recur (rest tokens)
                    end-ms
                    (conj timings {:word token
                                   :startSec (/ current-ms 1000)
                                   :endSec (/ end-ms 1000)})))

           (or (re-matches #"\s+" token)
               (re-matches #"[,.;:!?]" token))
           (let [duration-ms (phonemes-duration-ms [(pause-token token)] speech-rate)]
             (recur (rest tokens) (+ current-ms duration-ms) timings))

           :else
           (recur (rest tokens) current-ms timings)))))))
