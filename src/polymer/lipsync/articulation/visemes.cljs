(ns polymer.lipsync.articulation.visemes
  (:require [clojure.string :as str]
            [polymer.lipsync.state :as state]))

;; Polymer keeps the canonical 15-slot CC4/ARKit viseme order beside the CLJS
;; lip-sync code so numeric curves line up with Embody. This mirrors the
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

(def vowels
  #{"A" "E" "I" "O" "U"
    "AA" "AE" "AH" "AO" "AW" "AX" "AY"
    "EH" "ER" "EY"
    "IH" "IX" "IY"
    "OW" "OY"
    "UH" "UW"})

(defn primary-class [classes]
  (or (first classes) "default"))

(defn viseme-classes [viseme-id]
  ;; These are coarse visual-speech classes, not a replacement for phoneme
  ;; labels. Azure and other provider-only paths may only know a canonical
  ;; viseme slot, so this function gives the later JALI planners enough class
  ;; data to make conservative jaw/lip decisions.
  (cond
    (= viseme-id (:B_M_P canonical-visemes)) ["bilabial"]
    (= viseme-id (:F_V canonical-visemes)) ["labiodental" "fricative"]
    (= viseme-id (:S_Z canonical-visemes)) ["sibilant" "fricative"]
    (= viseme-id (:Th canonical-visemes)) ["dental" "fricative"]
    (= viseme-id (:Ch_J canonical-visemes)) ["obstruent" "tongue"]
    (= viseme-id (:K_G_H_NG canonical-visemes)) ["obstruent" "tongue"]
    (= viseme-id (:T_L_D_N canonical-visemes)) ["tongue"]
    (= viseme-id (:R canonical-visemes)) ["liquid"]
    (= viseme-id (:W_OO canonical-visemes)) ["glide" "lip-heavy"]
    (contains? #{(:AE canonical-visemes)
                 (:Ah canonical-visemes)
                 (:EE canonical-visemes)
                 (:Er canonical-visemes)
                 (:Ih canonical-visemes)
                 (:Oh canonical-visemes)}
               viseme-id) ["vowel"]
    :else ["default"]))

(defn phoneme-classes [phoneme viseme-id]
  ;; JALI's later jaw/lip planners need class metadata in addition to the
  ;; phoneme and canonical viseme slot. A phoneme can belong to multiple classes:
  ;; for example M is both bilabial and nasal, while F is labiodental and
  ;; fricative. Keep this data on every event so planner rules do not have to
  ;; reverse-engineer intent from a lossy viseme ID.
  (let [normalized (normalize-phoneme phoneme)]
    (cond
      (str/starts-with? (or phoneme "") "PAUSE_") ["pause"]
      (contains? vowels normalized) ["vowel"]
      (contains? #{"P" "B"} normalized) ["bilabial" "obstruent"]
      (= "M" normalized) ["bilabial" "nasal"]
      (contains? #{"F" "V"} normalized) ["labiodental" "fricative"]
      (contains? #{"S" "Z" "SH" "ZH"} normalized) ["sibilant" "fricative"]
      (contains? #{"TH" "DH"} normalized) ["dental" "fricative"]
      (contains? #{"T" "D" "K" "G" "CH" "JH"} normalized) ["obstruent" "tongue"]
      (contains? #{"N" "NG"} normalized) ["nasal" "tongue"]
      (contains? #{"L"} normalized) ["liquid" "tongue"]
      (contains? #{"R"} normalized) ["liquid"]
      (contains? #{"W"} normalized) ["glide" "lip-heavy"]
      (contains? #{"Y" "H" "HH"} normalized) ["glide"]
      :else (viseme-classes viseme-id))))

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
  ;; Align with Embody CC4_VISEME_SLOTS phoneme lists so Web Speech text
  ;; planning hits the same morphs Azure/provider IDs resolve to.
  {"sil" (:B_M_P canonical-visemes)
   "pau" (:B_M_P canonical-visemes)
   "PAUSE" (:B_M_P canonical-visemes)
   "A" (:Ah canonical-visemes)
   "E" (:AE canonical-visemes)
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
   ;; EH/EY/UH live on the AE slot in CC4 (not EE / W_OO).
   "EY" (:AE canonical-visemes)
   "EH" (:AE canonical-visemes)
   "UH" (:AE canonical-visemes)
   "ER" (:Er canonical-visemes)
   ;; Y/IY/IH/IX → EE to match Embody CC4 first-hit (EE lists IH/IX/Y before
   ;; the Ih slot). Azure id 6 also resolves to EE; keeping Web Speech on Ih
   ;; made short-i mouths diverge by provider.
   "Y" (:EE canonical-visemes)
   "IY" (:EE canonical-visemes)
   "IH" (:EE canonical-visemes)
   "IX" (:EE canonical-visemes)
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
   ;; SH/ZH are postalveolar → Ch_J in CC4, not the S_Z morph.
   "SH" (:Ch_J canonical-visemes)
   "CH" (:Ch_J canonical-visemes)
   "JH" (:Ch_J canonical-visemes)
   "ZH" (:Ch_J canonical-visemes)
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

(def web-speech-word-floor-ms 360)
(def web-speech-char-floor-ms 65)

(def pause-durations
  {"PAUSE_SPACE" 0
   "PAUSE_COMMA" 50
   "PAUSE_PERIOD" 100
   "PAUSE_QUESTION" 100
   "PAUSE_EXCLAMATION" 100
   "PAUSE_SEMICOLON" 75
   "PAUSE_COLON" 75})

(def diphthong-targets
  {;; Web Speech fallback starts from text, so it does not get provider
   ;; phoneme timing. Treat English diphthongs as internal lip travel inside
   ;; one vocalic jaw gesture: the lips move through two targets, but AU26
   ;; should stay on the same jaw arc instead of flapping twice.
   "EY" [(:AE canonical-visemes) (:EE canonical-visemes)]
   "OW" [(:Oh canonical-visemes) (:W_OO canonical-visemes)]
   "AW" [(:Ah canonical-visemes) (:W_OO canonical-visemes)]
   "OY" [(:Oh canonical-visemes) (:EE canonical-visemes)]
   "AY" [(:Ah canonical-visemes) (:Ih canonical-visemes)]})

(def diphthong-min-duration-ms 80)
(def diphthong-secondary-min-ms 36)

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
    ;; Map single letters onto distinct mouth families. Do not collapse o/u into
    ;; Ah (AA/AH) — that makes Web Speech text planning look like one open
    ;; vowel for almost every round vowel letter.
    (case letter
      "a" ["AE"]
      "e" ["EH"]
      "i" ["IH"]
      "o" ["OW"]
      "u" ["UW"]
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
  (into []
        (mapcat (fn [token]
                  (if (or (re-matches #"\s+" token)
                          (re-matches #"[,.;:!?]" token))
                    [(pause-token token)]
                    (word->phonemes token))))
        (tokenize text)))

(defn normalize-phoneme [phoneme]
  (-> (or phoneme "") str/upper-case (str/replace #"[0-9]" "")))

(defn vowel? [phoneme]
  (contains? vowels (normalize-phoneme phoneme)))

(defn phoneme->viseme-duration [phoneme]
  (if (str/starts-with? (or phoneme "") "PAUSE_")
    (let [classes (phoneme-classes phoneme (:B_M_P canonical-visemes))]
      {:phoneme phoneme
       :phonemeClass (primary-class classes)
       :phonemeClasses classes
       :viseme (:B_M_P canonical-visemes)
       :jawActivation 0
       :duration (get pause-durations phoneme 300)})
    (let [normalized (normalize-phoneme phoneme)
          viseme-id (get phoneme->viseme normalized (:B_M_P canonical-visemes))
          classes (phoneme-classes normalized viseme-id)
          fallback-duration (if (vowel? normalized) 50 35)]
      {:phoneme normalized
       :phonemeClass (primary-class classes)
       :phonemeClasses classes
       :viseme viseme-id
       :jawActivation (jaw-activation-for-phoneme normalized viseme-id)
       :duration (get phoneme-durations normalized fallback-duration)})))

(defn event-classes-for-target [phoneme viseme-id diphthong?]
  ;; A diphthong is one phoneme with internal lip travel. Keep the phoneme label
  ;; stable, but classify each generated target by the target viseme so the jaw
  ;; and lip planners know whether the segment is open, rounded, or spread.
  (let [classes (if diphthong?
                  (vec (distinct (conj (viseme-classes viseme-id) "diphthong")))
                  (phoneme-classes phoneme viseme-id))]
    {:phonemeClass (primary-class classes)
     :phonemeClasses classes}))

(defn diphthong-segments [phoneme viseme-id scaled-duration]
  (let [targets (get diphthong-targets phoneme)]
    (if (and targets (>= scaled-duration diphthong-min-duration-ms))
      (let [[first-viseme second-viseme] targets
            second-offset-ms (min (- scaled-duration diphthong-secondary-min-ms)
                                  (* scaled-duration 0.55))
            first-duration-ms (max diphthong-secondary-min-ms
                                   (min scaled-duration
                                        (+ second-offset-ms (* scaled-duration 0.22))))
            second-duration-ms (max diphthong-secondary-min-ms
                                    (- scaled-duration second-offset-ms))]
        [{:viseme first-viseme
          :relativeOffsetMs 0
          :durationMs first-duration-ms
          :diphthong true}
         {:viseme second-viseme
          :relativeOffsetMs second-offset-ms
          :durationMs second-duration-ms
          :diphthong true}])
      [{:viseme viseme-id
        :relativeOffsetMs 0
        :durationMs scaled-duration
        :diphthong false}])))

(defn phoneme-event [phoneme current-ms segment]
  (let [viseme-id (:viseme segment)
        class-data (event-classes-for-target phoneme viseme-id (:diphthong segment))]
    {:visemeId viseme-id
     :phoneme phoneme
     :phonemeClass (:phonemeClass class-data)
     :phonemeClasses (:phonemeClasses class-data)
     :jawActivation (if (:diphthong segment)
                      (jaw-activation-for-viseme viseme-id)
                      (jaw-activation-for-phoneme phoneme viseme-id))
     :offsetMs (+ current-ms (:relativeOffsetMs segment))
     :durationMs (:durationMs segment)}))

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
       (let [{:keys [phoneme viseme duration]} (phoneme->viseme-duration (first remaining))
             scaled-duration (adjust-duration duration speech-rate)]
         (if (<= scaled-duration 0)
           (recur (rest remaining) current-ms events)
           (recur (rest remaining)
                  (+ current-ms scaled-duration)
                  (into events
                        (map #(phoneme-event phoneme current-ms %))
                        (diphthong-segments phoneme viseme scaled-duration)))))))))

(defn word->visemes
  ([word] (word->visemes word 0 1))
  ([word start-ms speech-rate]
   (phonemes->visemes (word->phonemes word) start-ms speech-rate)))

(defn text->visemes
  ([text] (text->visemes text 1))
  ([text speech-rate]
   (phonemes->visemes (text->phonemes text) 0 speech-rate)))

(defn phonemes-duration-ms [phonemes speech-rate]
  (transduce
   (map (fn [phoneme]
          (adjust-duration (:duration (phoneme->viseme-duration phoneme)) speech-rate)))
   +
   0
   phonemes))

(defn word-tokens [text]
  (vec (re-seq #"[A-Za-z]+" (or text ""))))

(defn word-char-count [words]
  (transduce (map count) + 0 words))

(defn timeline-duration-ms [events]
  (transduce (map #(+ (:offsetMs %) (:durationMs %))) max 0 events))

(defn scale-event [scale event]
  (-> event
      (update :offsetMs #(js/Math.round (* scale %)))
      (update :durationMs #(max 1 (js/Math.round (* scale %))))))

(defn scale-events [events scale]
  (into [] (map #(scale-event scale %)) events))

(defn scale-word-timings [word-timings scale]
  (into []
        (map (fn [timing]
               (-> timing
                   (update :startSec #(* scale %))
                   (update :endSec #(* scale %)))))
        word-timings))

(defn web-speech-duration-ms
  "Estimate a Web Speech utterance floor from text shape, not just phoneme count.

  Browser Web Speech does not expose provider viseme timing or total audio
  duration. Short function words are especially under-estimated by pure phoneme
  sums, so use a conservative word/character floor and keep the longer of that
  floor and the phoneme-derived timeline. Boundary events can still correct
  drift later, but they should not immediately clamp a too-short phrase snippet
  to its end."
  [text speech-rate base-duration-ms]
  (let [rate (state/clamp 0.2 3 (state/number-or speech-rate 1))
        words (word-tokens text)
        word-floor (/ (* (count words) web-speech-word-floor-ms) rate)
        char-floor (/ (* (word-char-count words) web-speech-char-floor-ms) rate)
        floor-ms (max word-floor char-floor)]
    (js/Math.round (max base-duration-ms floor-ms))))

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
