(ns polymer.tts.azure
  (:require [clojure.string :as str]
            [polymer.lipsync.state :as state]
            [polymer.lipsync.articulation.visemes :as visemes]))

;; Azure Speech emits SAPI-style viseme IDs 0-21. Embody expects the
;; canonical 15-slot CC4/ARKit order. This namespace is pure normalization:
;; provider event data in, Polymer viseme timeline data out.
;;
;; This lives under TTS, not LipSync articulation, because Azure IDs are provider
;; facts. The namespace imports LipSync's canonical viseme definitions only to
;; output the provider-neutral timeline that LipSync can articulate like any
;; other text/timeline source.

(def azure->canonical
  {0 (:B_M_P visemes/canonical-visemes)
   1 (:AE visemes/canonical-visemes)
   2 (:Ah visemes/canonical-visemes)
   3 (:Oh visemes/canonical-visemes)
   4 (:Ih visemes/canonical-visemes)
   5 (:Er visemes/canonical-visemes)
   6 (:Ih visemes/canonical-visemes)
   7 (:W_OO visemes/canonical-visemes)
   8 (:Oh visemes/canonical-visemes)
   9 (:Ah visemes/canonical-visemes)
   10 (:Oh visemes/canonical-visemes)
   11 (:Ah visemes/canonical-visemes)
   12 (:K_G_H_NG visemes/canonical-visemes)
   13 (:R visemes/canonical-visemes)
   14 (:T_L_D_N visemes/canonical-visemes)
   15 (:S_Z visemes/canonical-visemes)
   16 (:Ch_J visemes/canonical-visemes)
   17 (:Th visemes/canonical-visemes)
   18 (:F_V visemes/canonical-visemes)
   19 (:T_L_D_N visemes/canonical-visemes)
   20 (:K_G_H_NG visemes/canonical-visemes)
   21 (:B_M_P visemes/canonical-visemes)})

(def min-duration-ms
  {:silence 0 :bilabial 55 :vowel 120 :fricative 80 :tongue 80 :liquid 90 :glide 100 :default 85})

(def max-duration-ms
  {:silence 0 :bilabial 110 :vowel 220 :fricative 150 :tongue 140 :liquid 160 :glide 180 :default 150})

(def max-overlap-ms
  {:silence 0 :bilabial 8 :vowel 90 :fricative 45 :tongue 45 :liquid 60 :glide 65 :default 45})

(def duplicate-window-ms 35)
(def diphthong-min-duration-ms 85)
(def diphthong-secondary-min-ms 38)

(def azure-vowels
  #{(:AE visemes/canonical-visemes)
    (:Ah visemes/canonical-visemes)
    (:EE visemes/canonical-visemes)
    (:Er visemes/canonical-visemes)
    (:Ih visemes/canonical-visemes)
    (:Oh visemes/canonical-visemes)
    (:W_OO visemes/canonical-visemes)})

(defn normalized-word [word]
  (-> (or word "") str/lower-case (str/replace #"[^a-z]" "")))

(defn viseme-class [provider-id canonical-id]
  (cond
    (= provider-id 0) :silence
    (= canonical-id (:B_M_P visemes/canonical-visemes)) :bilabial
    (contains? azure-vowels canonical-id) (if (= canonical-id (:W_OO visemes/canonical-visemes)) :glide :vowel)
    (#{(:Ch_J visemes/canonical-visemes)
       (:F_V visemes/canonical-visemes)
       (:S_Z visemes/canonical-visemes)
       (:Th visemes/canonical-visemes)} canonical-id) :fricative
    (#{(:K_G_H_NG visemes/canonical-visemes)
       (:T_L_D_N visemes/canonical-visemes)} canonical-id) :tongue
    (= canonical-id (:R visemes/canonical-visemes)) :liquid
    :else :default))

(defn normalize-provider-viseme [event]
  (let [id (or (:visemeId event) (:viseme_id event) (:id event) 0)
        time (or (:time event) (:audio_offset event) (:audioOffset event) 0)]
    (when (state/finite-number? time)
      {:visemeId id
       :time time})))

(defn normalize-provider-visemes [events]
  ;; This is the one place a local transducer is useful: normalize each provider
  ;; row and drop malformed rows in one pass. Sorting is intentionally outside
  ;; the transducer because ordering is a whole-collection operation.
  (->> (into [] (keep normalize-provider-viseme) (or events []))
       (sort-by :time)
       vec))

(defn normalize-azure-synthesis
  "Return the pure provider packet TTS uses after Azure synthesis.

  Audio fields are normalized here for playback. Visemes and word timings stay
  raw until the Azure provider mapper and LipSync agency normalize them into a
  provider-neutral animation timeline."
  [payload]
  {:audioBase64 (or (:audio_base64 payload) (:audioBase64 payload))
   :audioFormat (or (:audio_format payload) (:audioFormat payload))
   :durationSec (state/number-or (or (:durationSec payload)
                                     (:duration_sec payload)
                                     (:duration payload))
                                 0)
   :visemes (:visemes payload)
   :wordTimings (or (:word_boundaries payload)
                    (:wordTimings payload))})

(defn azure-synthesis->lipsync-command
  "Build the LipSync command from a normalized Azure synthesis packet."
  [snippet-name text payload config]
  {:type "processAzureVisemes"
   :name snippet-name
   :text text
   :source "azure"
   :visemes (:visemes payload)
   :wordTimings (or (:wordTimings payload)
                    (:word_timings payload)
                    (:word_boundaries payload))
   :totalDurationMs (js/Math.round (* (state/number-or (:durationSec payload) 0) 1000))
   :options {:visualLeadMs (:visualLeadMs config)}})

(defn word-start-sec [word]
  (state/number-or (or (:startSec word) (:start word) (:start_time word)) 0))

(defn word-end-sec [word]
  (state/number-or (or (:endSec word) (:end word) (:end_time word)) (word-start-sec word)))

(defn clean-word [word]
  (when word
    (let [value (.trim (str word))]
      (when (pos? (count value)) value))))

(defn normalize-word-timing [timing]
  (let [word (clean-word (:word timing))
        start-sec (max 0 (word-start-sec timing))
        end-sec (max start-sec (word-end-sec timing))]
    (when word
      {:word word
       :startSec start-sec
       :endSec end-sec})))

(defn normalize-word-timings [word-timings]
  (into [] (keep normalize-word-timing) (or word-timings [])))

(defn find-word-at-time [time-sec word-timings]
  (some (fn [word]
          (when (and (>= time-sec (- (word-start-sec word) 0.02))
                     (<= time-sec (+ (word-end-sec word) 0.02)))
            word))
        word-timings))

(defn long-e-word? [word]
  (or (boolean (re-find #"(?:ee|ea|ie|ei)" word))
      (contains? #{"we" "me" "be" "he" "she" "see"} word)
      (str/ends-with? word "y")))

(defn rounded-back? [event-time-sec word]
  (let [text (normalized-word (:word word))]
    (and (pos? (count text))
         (boolean (re-find #"(?:oo|ew|ue|ui|ough|ow|oa|oe|ose|ole|old|own|o)$" text))
         (let [start-sec (word-start-sec word)
               end-sec (word-end-sec word)
               duration-sec (max 0.001 (- end-sec start-sec))
               progress (state/clamp 0 1 (/ (- event-time-sec start-sec) duration-sec))]
           (>= progress 0.35)))))

(defn dental-th? [event-time-sec word]
  (let [text (normalized-word (:word word))]
    (and (str/includes? text "th")
         (let [start-sec (word-start-sec word)
               end-sec (word-end-sec word)
               duration-sec (max 0.001 (- end-sec start-sec))
               progress (state/clamp 0 1 (/ (- event-time-sec start-sec) duration-sec))
               positions (loop [index (str/index-of text "th")
                                result []]
                           (if (nil? index)
                             result
                             (recur (str/index-of text "th" (inc index)) (conj result index))))]
           (some (fn [index]
                   (let [th-progress (if (<= (count text) 2) 0 (/ index (max 1 (- (count text) 2))))
                         starts-word? (= index 0)
                         ends-word? (>= index (- (count text) 2))]
                     (or (and starts-word? (<= progress 0.45))
                         (and ends-word? (>= progress 0.55))
                         (<= (js/Math.abs (- progress th-progress)) 0.22))))
                 positions)))))

(defn refine-for-word [provider-id canonical-id event-time-sec word]
  (let [text (normalized-word (:word word))]
    (cond
      (= provider-id 0) nil
      (and (= provider-id 6) (long-e-word? text)) (:EE visemes/canonical-visemes)
      (and (= provider-id 4) (rounded-back? event-time-sec word)) (:W_OO visemes/canonical-visemes)
      (and (= provider-id 19) (dental-th? event-time-sec word)) (:Th visemes/canonical-visemes)
      :else canonical-id)))

(defn mapped-events [normalized options]
  (loop [remaining normalized
         result []]
    (if (empty? remaining)
      result
      (let [event (first remaining)
            provider-id (:visemeId event)
            base-canonical (get azure->canonical provider-id (:B_M_P visemes/canonical-visemes))
            word (find-word-at-time (:time event) (:wordTimings options))
            canonical-id (refine-for-word provider-id base-canonical (:time event) word)
            previous (last result)
            duplicate? (and previous
                            canonical-id
                            (= (:canonicalId previous) canonical-id)
                            (< (js/Math.abs (- (* 1000 (:time event))
                                               (* 1000 (:time previous))))
                               duplicate-window-ms))]
        (recur (rest remaining)
               (if (or (nil? canonical-id) duplicate?)
                 result
                 (conj result (assoc event
                                     :canonicalId canonical-id
                                     :className (viseme-class provider-id canonical-id)))))))))

(defn clamp-duration [duration-ms min-ms max-ms remaining-ms]
  (let [bounded (min (max duration-ms min-ms) max-ms remaining-ms)]
    (max 0 (js/Math.round bounded))))

(defn duration-ms [current next-event total-duration-ms]
  (let [offset-ms (max 0 (js/Math.round (* (:time current) 1000)))
        remaining-ms (if (state/finite-number? total-duration-ms)
                       (max 0 (- total-duration-ms offset-ms))
                       js/Number.POSITIVE_INFINITY)
        class-name (:className current)
        fallback-ms (if (= class-name :silence) (max-duration-ms :silence) (min-duration-ms class-name))
        raw-span-ms (if next-event
                      (max 0 (js/Math.round (* (- (:time next-event) (:time current)) 1000)))
                      (min fallback-ms remaining-ms))]
    (if (= class-name :silence)
      (clamp-duration raw-span-ms 0 (max-duration-ms :silence) remaining-ms)
      (let [next-closure? (= (:className next-event) :bilabial)
            overlap-ms (if next-closure? 8 (max-overlap-ms class-name))
            desired-ms (max raw-span-ms (min-duration-ms class-name))
            max-ms (min (max-duration-ms class-name) (+ raw-span-ms overlap-ms))]
        (clamp-duration desired-ms 1 max-ms remaining-ms)))))

(defn push-event
  ([timeline viseme-id offset-ms duration-ms]
   (push-event timeline viseme-id offset-ms duration-ms nil))
  ([timeline viseme-id offset-ms duration-ms extra-classes]
   (if (<= duration-ms 0)
     timeline
     (let [classes (vec (distinct (concat (visemes/viseme-classes viseme-id)
                                          (or extra-classes []))))]
       (conj timeline {:visemeId viseme-id
                       :phonemeClass (visemes/primary-class classes)
                       :phonemeClasses classes
                       :jawActivation (visemes/jaw-activation-for-viseme viseme-id)
                       :offsetMs (max 0 (js/Math.round offset-ms))
                       :durationMs (max 1 (js/Math.round duration-ms))})))))

(defn diphthong-targets [provider-id]
  (case provider-id
    8 [(:Oh visemes/canonical-visemes) (:W_OO visemes/canonical-visemes)]
    9 [(:Ah visemes/canonical-visemes) (:W_OO visemes/canonical-visemes)]
    10 [(:Oh visemes/canonical-visemes) (:EE visemes/canonical-visemes)]
    11 [(:Ah visemes/canonical-visemes) (:Ih visemes/canonical-visemes)]
    nil))

(defn push-expanded-event [timeline provider-id canonical-id offset-ms duration-ms]
  (let [targets (diphthong-targets provider-id)]
    (if (or (nil? targets) (< duration-ms diphthong-min-duration-ms))
      (push-event timeline canonical-id offset-ms duration-ms)
      (let [[first-viseme second-viseme] targets
            second-offset-ms (min (+ offset-ms duration-ms (- diphthong-secondary-min-ms))
                                  (+ offset-ms (* duration-ms 0.55)))
            first-duration-ms (max diphthong-secondary-min-ms
                                   (min duration-ms (+ (- second-offset-ms offset-ms)
                                                       (* duration-ms 0.25))))
            second-duration-ms (max diphthong-secondary-min-ms
                                    (- (+ offset-ms duration-ms) second-offset-ms))]
        ;; Azure provider IDs 8-11 represent diphthong motion. Preserve that
        ;; fact after expansion so the jaw planner keeps one vocalic jaw arc
        ;; while the lips travel through the two canonical targets.
        (-> timeline
            (push-event first-viseme offset-ms first-duration-ms ["diphthong"])
            (push-event second-viseme second-offset-ms second-duration-ms ["diphthong"]))))))

(defn azure-visemes->timeline
  ([visemes] (azure-visemes->timeline visemes nil nil))
  ([visemes total-duration-ms options]
   (let [options (or options {})
         normalized (normalize-provider-visemes visemes)
         mapped (mapped-events normalized options)]
     (->> (map-indexed (fn [index event]
                         {:event event
                          :next-event (get mapped (inc index))})
                       mapped)
          (reduce (fn [timeline {:keys [event next-event]}]
                    (let [offset-ms (max 0 (js/Math.round (* (:time event) 1000)))
                          event-duration-ms (duration-ms event next-event total-duration-ms)]
                      (push-expanded-event timeline
                                           (:visemeId event)
                                           (:canonicalId event)
                                           offset-ms
                                           event-duration-ms)))
                  [])
          (sort-by :offsetMs)
          vec))))
