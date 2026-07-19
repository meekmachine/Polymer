(ns polymer.core
  (:require [polymer.animation.agency :as animation]
            [polymer.blink.agency :as blink]
            [polymer.character :as character]
            [polymer.eye-head.agency :as eye-head]
            [polymer.gaze.agency :as gaze]
            [polymer.tts.agency :as tts]
            [polymer.lipsync.agency :as lipsync]
            [polymer.prosodic.agency :as prosodic]))

;; Public JavaScript entry points.
;;
;; Polymer is authored in CLJS, but LoomLarge imports the compiled package from
;; JavaScript/TypeScript. The exports stay agency-oriented: LoomLarge can create
;; a character agency network, but Polymer owns the cross-agency routing and
;; Animation owns the Loom3/Embody runtime calls.

(defn createBlinkAgency
  "Create the Blink agency directly. Use createCharacterAgencies when Blink
  should route animation intent to Polymer Animation."
  ([] (blink/create-blink-agency nil))
  ([config] (blink/create-blink-agency config)))

(defn createAnimationAgency
  "Create the Animation agency directly. Pass an Embody runtime or Loom3 engine
  in config when the agency should execute snippets."
  ([] (animation/create-animation-agency nil))
  ([config] (animation/create-animation-agency config)))

(defn createGazeAgency
  "Create the Gaze agency directly. Use createCharacterAgencies when Gaze
  should participate in the per-character agency network."
  ([] (gaze/create-gaze-agency nil))
  ([config] (gaze/create-gaze-agency config)))

(defn createEyeHeadTrackingAgency
  "Create the Eye/Head Tracking agency directly."
  ([] (eye-head/create-eye-head-tracking-agency nil))
  ([config] (eye-head/create-eye-head-tracking-agency config)))

(defn createLipSyncAgency
  "Create the LipSync agency directly. Use createCharacterAgencies when
  LipSync should route viseme animation intent to Polymer Animation."
  ([] (lipsync/create-lipsync-agency nil))
  ([config] (lipsync/create-lipsync-agency config)))

(defn createTTSAgency
  "Create the TTS agency directly. Use createCharacterAgencies when TTS should
  route speech timing facts into Polymer LipSync."
  ([] (tts/create-tts-agency nil))
  ([config] (tts/create-tts-agency config)))

(defn createProsodicAgency
  "Create the Prosodic Expression agency directly. Use createCharacterAgencies
  when speech and blink facts should route to prosody inside Polymer."
  ([] (prosodic/create-prosodic-agency nil))
  ([config] (prosodic/create-prosodic-agency config)))

(defn createCharacterAgencies
  "Create the per-character Polymer agency network."
  ([] (character/create-character-agencies nil))
  ([config] (character/create-character-agencies config)))
