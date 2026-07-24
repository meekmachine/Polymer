(ns polymer.core
  (:require [polymer.animation.agency :as animation]
            [polymer.animation.service :as animation-service]
            [polymer.blink.agency :as blink]
            [polymer.camera-context.agency :as camera-context]
            [polymer.character :as character]
            [polymer.conversation.agency :as conversation]
            [polymer.conversation.service :as conversation-service]
            [polymer.eye-head.agency :as eye-head]
            [polymer.eye-head.service :as eye-head-service]
            [polymer.gesture.agency :as gesture]
            [polymer.gaze.agency :as gaze]
            [polymer.hair.agency :as hair]
            [polymer.hair.service :as hair-service]
            [polymer.tts.agency :as tts]
            [polymer.lipsync.agency :as lipsync]
            [polymer.emphatic.agency :as emphatic]
            [polymer.prosodic.agency :as prosodic]
            [polymer.transcription.agency :as transcription]
            [polymer.transcription.service :as transcription-service]
            [polymer.society :as society]))

(def DEFAULT_AGENCY_SOCIETY society/DEFAULT_AGENCY_SOCIETY)

;; Public JavaScript entry points.
;;
;; Polymer is authored in CLJS, but LoomLarge imports the compiled package from
;; JavaScript/TypeScript. The exports stay agency-oriented: LoomLarge can create
;; a character agency network, but Polymer owns the cross-agency routing and
;; Animation owns the Embody runtime calls.

(defn createBlinkAgency
  "Create the Blink agency directly. Use createCharacterAgencies when Blink
  should route animation intent to Polymer Animation."
  ([] (blink/create-blink-agency nil))
  ([config] (blink/create-blink-agency config)))

(defn createAnimationAgency
  "Create the Animation agency directly. Pass an Embody runtime or engine in
  config when the agency should execute snippets."
  ([] (animation/create-animation-agency nil))
  ([config] (animation/create-animation-agency config)))

(defn createAnimationService
  "Create the JS compatibility animation API backed by Polymer Animation.
  LoomLarge uses this while it is still calling the historical animation manager
  shape; snippets still enter Polymer's agency-local planner and scheduler."
  [engine]
  (animation-service/createAnimationService engine))

(defn createEyeHeadTrackingService
  "Create the JS compatibility Eye/Head Tracking API backed by Polymer
  Eye/Head. Accepted movement requests route through Polymer Animation."
  ([] (eye-head-service/createEyeHeadTrackingService nil nil))
  ([config] (eye-head-service/createEyeHeadTrackingService config nil))
  ([config callbacks] (eye-head-service/createEyeHeadTrackingService config callbacks)))

(defn createTranscriptionService
  "Create the JS compatibility transcription API backed by Polymer
  Transcription. Browser SpeechRecognition is owned inside this package."
  ([] (transcription-service/createTranscriptionService nil nil))
  ([config] (transcription-service/createTranscriptionService config nil))
  ([config callbacks] (transcription-service/createTranscriptionService config callbacks)))

(defn createConversationService
  "Create the JS compatibility conversation API backed by Polymer
  Conversation."
  ([tts transcription] (conversation-service/createConversationService tts transcription nil nil))
  ([tts transcription config] (conversation-service/createConversationService tts transcription config nil))
  ([tts transcription config callbacks] (conversation-service/createConversationService tts transcription config callbacks)))

(defn HairService
  ([] (hair-service/HairService nil))
  ([engine] (hair-service/HairService engine)))

(def TranscriptionService transcription-service/TranscriptionService)
(def ConversationService conversation-service/ConversationService)
(def HAIR_COLOR_PRESETS hair-service/HAIR_COLOR_PRESETS)
(def DEFAULT_HAIR_PHYSICS_CONFIG hair-service/DEFAULT_HAIR_PHYSICS_CONFIG)
(def DEFAULT_HAIR_PHYSICS_ENABLED hair-service/DEFAULT_HAIR_PHYSICS_ENABLED)
(def DEFAULT_EYE_HEAD_CONFIG eye-head-service/DEFAULT_EYE_HEAD_CONFIG)
(def DEFAULT_ANIMATION_KEYS eye-head-service/DEFAULT_ANIMATION_KEYS)
(def EYE_AUS eye-head-service/EYE_AUS)
(def HEAD_AUS eye-head-service/HEAD_AUS)

(def animationEventEmitter animation-service/animationEventEmitter)
(def snippetList$ animation-service/snippetList$)
(def globalPlaybackState$ animation-service/globalPlaybackState$)
(def bakedClipList$ animation-service/bakedClipList$)
(def playingBakedAnimations$ animation-service/playingBakedAnimations$)
(def bakedAnimationProgress$ animation-service/bakedAnimationProgress$)

(defn snippetState$ [snippet-name]
  (animation-service/snippetState$ snippet-name))

(defn snippetTime$ [snippet-name]
  (animation-service/snippetTime$ snippet-name))

(defn bakedAnimationState$ [clip-name]
  (animation-service/bakedAnimationState$ clip-name))

(defn getBundledSnippetNames [list-key]
  (animation-service/getBundledSnippetNames list-key))

(defn getStoredSnippetNames
  ([list-key] (animation-service/getStoredSnippetNames list-key))
  ([list-key storage] (animation-service/getStoredSnippetNames list-key storage)))

(defn getAvailableSnippetNames
  ([list-key] (animation-service/getAvailableSnippetNames list-key))
  ([list-key storage] (animation-service/getAvailableSnippetNames list-key storage)))

(defn resolveSnippetEntry
  ([list-key name] (animation-service/resolveSnippetEntry list-key name))
  ([list-key name storage] (animation-service/resolveSnippetEntry list-key name storage)))

(defn preloadAllSnippets []
  (animation-service/preloadAllSnippets))

(defn clearPreloadedSnippets
  ([] (animation-service/clearPreloadedSnippets))
  ([storage] (animation-service/clearPreloadedSnippets storage)))

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

(defn createGestureAgency
  "Create the Gesture agency directly. Use createCharacterAgencies when Gesture
  should route arm/hand animation intent to Polymer Animation."
  ([] (gesture/create-gesture-agency nil))
  ([config] (gesture/create-gesture-agency config)))

(defn createProsodicAgency
  "Create the Prosodic Expression agency directly. Use createCharacterAgencies
  when speech and blink facts should route to prosody inside Polymer."
  ([] (prosodic/create-prosodic-agency nil))
  ([config] (prosodic/create-prosodic-agency config)))

(defn createEmphaticAgency
  "Create the Emphatic Expression agency directly. Use createCharacterAgencies
  when conversation/TTS facts should drive linguistic stress gestures."
  ([] (emphatic/create-emphatic-agency nil))
  ([config] (emphatic/create-emphatic-agency config)))

(defn createConversationAgency
  "Create the Conversation agency directly. Use createCharacterAgencies when
  transcript facts should route into Conversation and Conversation speech
  requests should route to TTS inside Polymer."
  ([] (conversation/create-conversation-agency nil))
  ([config] (conversation/create-conversation-agency config)))

(defn createTranscriptionAgency
  "Create the Transcription agency directly. Use createCharacterAgencies when
  final transcript facts should route to Conversation inside Polymer."
  ([] (transcription/create-transcription-agency nil))
  ([config] (transcription/create-transcription-agency config)))

(defn createHairAgency
  "Create the Hair agency directly."
  ([] (hair/create-hair-agency nil))
  ([config] (hair/create-hair-agency config)))

(defn createCameraContextAgency
  "Create the Camera Context agency directly. Use createCharacterAgencies when
  camera-relative facts should route to Gaze inside Polymer."
  ([] (camera-context/create-camera-context-agency nil))
  ([config] (camera-context/create-camera-context-agency config)))

(defn createCharacterAgencies
  "Create the per-character Polymer agency network."
  ([] (character/create-character-agencies nil))
  ([config] (character/create-character-agencies config)))
