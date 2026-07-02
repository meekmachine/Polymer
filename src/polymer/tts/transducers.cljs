(ns polymer.tts.transducers
  (:require [polymer.tts.state :as state]))

;; Transducers keep provider payload cleanup as pure data transformation.
;; The agency calls these before any playback or LipSync events are emitted.
;;
;; This file is intentionally side-effect free. It does not fetch audio, start
;; playback, touch DOM APIs, or dispatch to another agency. It accepts raw data
;; from browser/backend providers and returns the normalized facts that TTS,
;; Vocal/LipSync, and tests can reason about deterministically.

(defn finite
  "Coerce a provider number while preserving a clean fallback."
  [value fallback]
  (if (state/finite-number? value) value fallback))

(defn provider-id
  "Read the Azure viseme id from any provider/backend field variant."
  [event]
  (or (:viseme_id event)
      (:visemeId event)
      (:id event)
      0))

(defn provider-time
  "Read the Azure audio offset from any provider/backend field variant."
  [event]
  (or (:audio_offset event)
      (:audioOffset event)
      (:time event)
      0))

(defn normalize-azure-viseme
  "Convert one backend/Azure viseme event into Polymer provider input."
  [event]
  {:id (int (finite (provider-id event) 0))
   :time (max 0 (finite (provider-time event) 0))})

(def azure-viseme-xf
  ;; Keep this as a named transducer so tests can prove the pipeline shape.
  (comp
   (map normalize-azure-viseme)
   (filter #(and (state/finite-number? (:id %))
                 (state/finite-number? (:time %))))))

(defn normalize-azure-visemes
  "Normalize and sort all Azure viseme events through the transducer pipeline."
  [events]
  (sort-by :time (into [] azure-viseme-xf (or events []))))

(defn normalize-word-boundary
  "Convert backend/provider word timing into the Vocal/LipSync timing surface."
  [boundary]
  {:word (or (:word boundary) "")
   :startSec (max 0 (finite (or (:startSec boundary)
                                (:start boundary)
                                (:start_time boundary))
                            0))
   :endSec (max 0 (finite (or (:endSec boundary)
                              (:end boundary)
                              (:end_time boundary))
                          0))})

(def word-boundary-xf
  ;; Word timings are pure data; invalid rows are dropped before LipSync sees them.
  (comp
   (map normalize-word-boundary)
   (filter #(and (pos? (count (:word %)))
                 (>= (:endSec %) (:startSec %))))))

(defn normalize-word-boundaries
  "Normalize all word boundaries through a composable transducer."
  [boundaries]
  (into [] word-boundary-xf (or boundaries [])))

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
  "Return the exact pure data packet the TTS agency uses after Azure synthesis."
  [payload]
  {:audioBase64 (or (:audio_base64 payload) (:audioBase64 payload))
   :audioFormat (or (:audio_format payload) (:audioFormat payload))
   :durationSec (finite (or (:durationSec payload) (:duration_sec payload) (:duration payload)) 0)
   :visemes (normalize-azure-visemes (:visemes payload))
   :wordTimings (normalize-word-boundaries (or (:word_boundaries payload)
                                               (:wordTimings payload)))})

(defn azure-synthesis->lipsync-command
  "Build the Vocal/LipSync command from an already-normalized Azure packet."
  [snippet-name text payload config]
  ;; TTS owns provider timing facts, but Vocal owns mouth animation construction.
  ;; This helper keeps the bridge as plain data so the agency does not hide any
  ;; morph/jaw logic while it is also dealing with playback side effects.
  {:type "processAzureVisemes"
   :name snippet-name
   :text text
   :source "azure"
   :visemes (:visemes payload)
   :wordTimings (:wordTimings payload)
   :totalDurationMs (js/Math.round (* (:durationSec payload) 1000))
   :options {:visualLeadMs (:visualLeadMs config)}})
