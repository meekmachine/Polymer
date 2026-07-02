(ns polymer.lipsync.transducers
  (:require [polymer.lipsync.state :as state]))

;; LipSync transducers own provider timing cleanup. TTS can pass provider facts
;; through, but LipSync decides how raw Azure/Web Speech timing becomes the
;; normalized data used by the mouth animation planner.

(defn finite
  [value fallback]
  (if (state/finite-number? value) value fallback))

(defn provider-id
  [event]
  (or (:viseme_id event)
      (:visemeId event)
      (:id event)
      0))

(defn provider-time
  [event]
  (or (:audio_offset event)
      (:audioOffset event)
      (:time event)
      0))

(defn normalize-azure-viseme
  "Normalize one Azure/provider viseme event without mapping it to a mouth pose."
  [event]
  {:id (int (finite (provider-id event) 0))
   :time (max 0 (finite (provider-time event) 0))})

(def azure-viseme-xf
  ;; Provider viseme timing cleanup is a good fit for a transducer because each
  ;; input row is independent: normalize the field names, discard malformed rows,
  ;; then let the caller decide whether to sort/materialize.
  (comp
   (map normalize-azure-viseme)
   (filter #(and (state/finite-number? (:id %))
                 (state/finite-number? (:time %))))))

(defn normalize-azure-visemes
  "Normalize and sort provider viseme timing before articulation mapping."
  [events]
  (sort-by :time (into [] azure-viseme-xf (or events []))))

(defn normalize-word-boundary
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
  ;; Word timings follow the same pattern. This does not infer mouth shapes; it
  ;; only produces the canonical timing facts used later for drift correction.
  (comp
   (map normalize-word-boundary)
   (filter #(and (pos? (count (:word %)))
                 (>= (:endSec %) (:startSec %))))))

(defn normalize-word-boundaries
  "Normalize all provider word timings through one composable pipeline."
  [boundaries]
  (into [] word-boundary-xf (or boundaries [])))

(defn normalize-process-azure-command
  "Normalize the provider-facing fields of a processAzureVisemes command."
  [command]
  ;; This is the handoff point between provider facts and articulation. After
  ;; this call, azure.cljs can assume stable :id/:time visemes and
  ;; :word/:startSec/:endSec word timings.
  (assoc command
         :visemes (normalize-azure-visemes (:visemes command))
         :wordTimings (normalize-word-boundaries (or (:wordTimings command)
                                                     (get-in command [:options :wordTimings])))))

(defn azure-synthesis->lipsync-command
  "Build the LipSync command from a TTS Azure synthesis packet."
  [snippet-name text payload config]
  {:type "processAzureVisemes"
   :name snippet-name
   :text text
   :source "azure"
   :visemes (:visemes payload)
   :wordTimings (or (:wordTimings payload) (:word_timings payload) (:word_boundaries payload))
   :totalDurationMs (js/Math.round (* (finite (:durationSec payload) 0) 1000))
   :options {:visualLeadMs (:visualLeadMs config)}})
