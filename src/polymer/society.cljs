(ns polymer.society)

;; Default agencySociety seed for hosts and character profiles.
;;
;; Mirrors today's hardcoded polymer.character routing. When character.cljs
;; gains or drops a route, update this seed in the same change.
;;
;; See docs/agency-architecture.md (Agency Society Config) and issues #63/#64.

(def agency-names
  ["animation"
   "blink"
   "gaze"
   "eyeHeadTracking"
   "gesture"
   "cameraContext"
   "transcription"
   "conversation"
   "hair"
   "tts"
   "lipSync"
   "prosodic"
   "emphatic"])

(def required-agency-names
  #{"animation" "transcription" "tts" "lipSync" "conversation"})

(defn- agency-entry [name]
  {:required (contains? required-agency-names name)
   :enabled true
   :priors {}
   :configure {}})

(defn- edge
  ([id from type to required]
   (edge id from type to required nil))
  ([id from type to required match]
   (cond-> {:id id
            :from from
            :type type
            :to to
            :required required
            :enabled true
            :credit 1.0}
     match (assoc :match match))))

(def default-edges
  [;; Perception → gaze → motor
   (edge "cameraContext.camera.fact→gaze" "cameraContext" "camera.fact" "gaze" true)
   (edge "cameraContext.camera.stale→gaze" "cameraContext" "camera.stale" "gaze" true)
   (edge "gaze.eyeHeadTracking.requestGaze→eyeHeadTracking" "gaze" "eyeHeadTracking.requestGaze" "eyeHeadTracking" true)
   (edge "gaze.eyeHeadTracking.requestReset→eyeHeadTracking" "gaze" "eyeHeadTracking.requestReset" "eyeHeadTracking" true)
   (edge "gaze.eyeHeadTracking.requestCancel→eyeHeadTracking" "gaze" "eyeHeadTracking.requestCancel" "eyeHeadTracking" true)

   ;; Embodiment → animation apply gate
   (edge "blink.animation.requestScheduleSnippet→animation" "blink" "animation.requestScheduleSnippet" "animation" true)
   (edge "eyeHeadTracking.animation.requestScheduleSnippet→animation" "eyeHeadTracking" "animation.requestScheduleSnippet" "animation" true)
   (edge "eyeHeadTracking.animation.requestRemoveSnippet→animation" "eyeHeadTracking" "animation.requestRemoveSnippet" "animation" true)
   (edge "gesture.animation.requestScheduleSnippet→animation" "gesture" "animation.requestScheduleSnippet" "animation" true)
   (edge "gesture.animation.requestRemoveSnippet→animation" "gesture" "animation.requestRemoveSnippet" "animation" true)
   (edge "lipSync.animation.requestScheduleSnippet→animation" "lipSync" "animation.requestScheduleSnippet" "animation" true)
   (edge "lipSync.animation.requestRemoveSnippet→animation" "lipSync" "animation.requestRemoveSnippet" "animation" true)
   (edge "lipSync.animation.requestSeekSnippet→animation" "lipSync" "animation.requestSeekSnippet" "animation" true)
   (edge "prosodic.animation.requestScheduleSnippet→animation" "prosodic" "animation.requestScheduleSnippet" "animation" true)
   (edge "prosodic.animation.requestRemoveSnippet→animation" "prosodic" "animation.requestRemoveSnippet" "animation" true)
   (edge "emphatic.animation.requestScheduleSnippet→animation" "emphatic" "animation.requestScheduleSnippet" "animation" true)
   (edge "emphatic.animation.requestRemoveSnippet→animation" "emphatic" "animation.requestRemoveSnippet" "animation" true)

   ;; Blink ↔ prosodic coupling (seeded; not required — may be disabled per character)
   (edge "blink.signal.blink-fast→prosodic" "blink" "signal" "prosodic" false {:signal "blink-fast"})

   ;; Speech discourse (required cores)
   (edge "transcription.transcription.final→conversation" "transcription" "transcription.final" "conversation" true)
   (edge "transcription.transcription.interruption→conversation" "transcription" "transcription.interruption" "conversation" true)
   (edge "conversation.tts.requestSpeak→tts" "conversation" "tts.requestSpeak" "tts" true)
   (edge "conversation.conversation.cancelRequested→tts" "conversation" "conversation.cancelRequested" "tts" true)
   (edge "conversation.conversation.userUtterance→prosodic" "conversation" "conversation.userUtterance" "prosodic" false)
   (edge "conversation.conversation.agentUtterance→prosodic" "conversation" "conversation.agentUtterance" "prosodic" false)
   (edge "conversation.conversation.requestResponse→prosodic" "conversation" "conversation.requestResponse" "prosodic" false)
   (edge "conversation.conversation.cancelRequested→prosodic" "conversation" "conversation.cancelRequested" "prosodic" false)
   (edge "conversation.conversation.userUtterance→emphatic" "conversation" "conversation.userUtterance" "emphatic" false)
   (edge "conversation.conversation.agentUtterance→emphatic" "conversation" "conversation.agentUtterance" "emphatic" false)
   (edge "conversation.conversation.cancelRequested→emphatic" "conversation" "conversation.cancelRequested" "emphatic" false)
   (edge "tts.lipSync.command→lipSync" "tts" "lipSync.command" "lipSync" true)
   (edge "tts.ttsStatusChanged→conversation" "tts" "ttsStatusChanged" "conversation" true)
   (edge "tts.ttsStatusChanged→transcription" "tts" "ttsStatusChanged" "transcription" true)
   (edge "tts.ttsSpeechStarted→prosodic" "tts" "ttsSpeechStarted" "prosodic" true)
   (edge "tts.ttsSpeechStarted→emphatic" "tts" "ttsSpeechStarted" "emphatic" true)
   (edge "tts.ttsSpeechStarted→transcription" "tts" "ttsSpeechStarted" "transcription" true)
   (edge "tts.ttsSpeechStarted→conversation" "tts" "ttsSpeechStarted" "conversation" true)
   (edge "tts.ttsWordBoundary→prosodic" "tts" "ttsWordBoundary" "prosodic" true)
   (edge "tts.ttsWordBoundary→emphatic" "tts" "ttsWordBoundary" "emphatic" true)
   (edge "tts.ttsSpeechStopped→prosodic" "tts" "ttsSpeechStopped" "prosodic" true)
   (edge "tts.ttsSpeechStopped→emphatic" "tts" "ttsSpeechStopped" "emphatic" true)
   (edge "tts.ttsSpeechStopped→transcription" "tts" "ttsSpeechStopped" "transcription" true)
   (edge "tts.ttsSpeechStopped→conversation" "tts" "ttsSpeechStopped" "conversation" true)
   (edge "tts.ttsSpeechEnded→prosodic" "tts" "ttsSpeechEnded" "prosodic" true)
   (edge "tts.ttsSpeechEnded→emphatic" "tts" "ttsSpeechEnded" "emphatic" true)
   (edge "tts.ttsSpeechEnded→transcription" "tts" "ttsSpeechEnded" "transcription" true)
   (edge "tts.ttsSpeechEnded→conversation" "tts" "ttsSpeechEnded" "conversation" true)])

;; Hair currently fans out to host/runtime only (no agency→agency route in
;; character.cljs). Keep the agency in the seed; add edges when hair is wired
;; into the discourse network.

(def default-agency-society
  {:version 1
   :agencies (into {} (map (fn [name] [name (agency-entry name)]) agency-names))
   :edges default-edges
   :kLines []
   :cb5t {:E 0.5 :N 0.5 :C 0.5 :A 0.5 :O 0.5}
   :personaPackId nil
   :characterRollup {:type "weightedSum"}})

(def DEFAULT_AGENCY_SOCIETY
  "JS-facing default agencySociety seed (plain JS object)."
  (clj->js default-agency-society))

(defn default-edge-ids
  "Return the set of default edge ids for sync tests."
  []
  (set (map :id default-edges)))
