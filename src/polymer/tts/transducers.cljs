(ns polymer.tts.transducers
  (:require [polymer.tts.state :as state]))

;; TTS provider transforms are side-effect free, but they stay limited to the
;; provider/session concerns TTS owns: audio fields and voice lists. Provider
;; viseme mapping lives beside the Azure provider mapper in polymer.tts.azure.

(defn finite
  "Coerce a provider number while preserving a clean fallback."
  [value fallback]
  (if (state/finite-number? value) value fallback))

(defn normalize-voice
  "Normalize Web Speech/Azure voice objects into one UI-friendly shape."
  [voice provider]
  {:id (or (:id voice) (:name voice) (:shortName voice) "")
   :name (or (:name voice) (:localName voice) (:id voice) "")
   :language (or (:language voice) (:lang voice) (:locale voice) "")
   :gender (or (:gender voice) "")
   :styles (vec (or (:styles voice) []))
   :provider provider})

(defn voice-xf
  "Build the provider-specific voice normalization transducer."
  [provider]
  ;; A voice is useful to the UI only when it has a stable id. Keeping this as a
  ;; real transducer means large provider lists can be mapped/filtered in one pass.
  (comp
   (map #(normalize-voice % provider))
   (filter #(pos? (count (:id %))))))

(defn normalize-voices
  "Normalize voice choices and drop empty placeholder rows."
  [voices provider]
  (into [] (voice-xf provider) (or voices [])))

(defn normalize-azure-synthesis
  "Return the pure provider packet TTS uses after Azure synthesis.

  Audio fields are normalized here for playback. Visemes and word timings stay
  raw so LipSync can own the provider timing transducers and articulation plan."
  [payload]
  {:audioBase64 (or (:audio_base64 payload) (:audioBase64 payload))
   :audioFormat (or (:audio_format payload) (:audioFormat payload))
   :durationSec (finite (or (:durationSec payload) (:duration_sec payload) (:duration payload)) 0)
   :visemes (:visemes payload)
   :wordTimings (or (:word_boundaries payload)
                    (:wordTimings payload))})
