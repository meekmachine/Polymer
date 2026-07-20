(ns polymer.society-test
  (:require [cljs.test :refer [deftest is]]
            [polymer.core :as polymer]
            [polymer.society :as society]))

(def expected-route-edge-ids
  #{;; camera → gaze → eyeHead
    "cameraContext.camera.fact→gaze"
    "cameraContext.camera.stale→gaze"
    "gaze.eyeHeadTracking.requestGaze→eyeHeadTracking"
    "gaze.eyeHeadTracking.requestReset→eyeHeadTracking"
    "gaze.eyeHeadTracking.requestCancel→eyeHeadTracking"
    ;; embodiment → animation
    "blink.animation.requestScheduleSnippet→animation"
    "eyeHeadTracking.animation.requestScheduleSnippet→animation"
    "eyeHeadTracking.animation.requestRemoveSnippet→animation"
    "gesture.animation.requestScheduleSnippet→animation"
    "gesture.animation.requestRemoveSnippet→animation"
    "lipSync.animation.requestScheduleSnippet→animation"
    "lipSync.animation.requestRemoveSnippet→animation"
    "lipSync.animation.requestSeekSnippet→animation"
    "prosodic.animation.requestScheduleSnippet→animation"
    "prosodic.animation.requestRemoveSnippet→animation"
    ;; blink → prosodic
    "blink.signal.blink-fast→prosodic"
    ;; speech discourse
    "transcription.transcription.final→conversation"
    "transcription.transcription.interruption→conversation"
    "conversation.tts.requestSpeak→tts"
    "conversation.conversation.cancelRequested→tts"
    "tts.lipSync.command→lipSync"
    "tts.ttsStatusChanged→conversation"
    "tts.ttsStatusChanged→transcription"
    "tts.ttsSpeechStarted→prosodic"
    "tts.ttsSpeechStarted→transcription"
    "tts.ttsSpeechStarted→conversation"
    "tts.ttsWordBoundary→prosodic"
    "tts.ttsSpeechStopped→prosodic"
    "tts.ttsSpeechStopped→transcription"
    "tts.ttsSpeechStopped→conversation"
    "tts.ttsSpeechEnded→prosodic"
    "tts.ttsSpeechEnded→transcription"
    "tts.ttsSpeechEnded→conversation"})

(deftest default-agency-society-covers-character-router-edges
  (is (= expected-route-edge-ids (society/default-edge-ids)))
  (is (= expected-route-edge-ids
         (set (map :id (:edges society/default-agency-society))))))

(deftest default-agency-society-marks-required-cores
  (let [agencies (:agencies society/default-agency-society)]
    (doseq [name ["animation" "transcription" "tts" "lipSync" "conversation"]]
      (is (true? (get-in agencies [name :required])) name))
    (is (false? (get-in agencies ["blink" :required])))
    (is (false? (get-in agencies ["hair" :required])))))

(deftest default-agency-society-js-export-shape
  (let [js-society polymer/DEFAULT_AGENCY_SOCIETY
        clj-society (js->clj js-society :keywordize-keys true)]
    (is (= 1 (:version clj-society)))
    (is (map? (:agencies clj-society)))
    (is (sequential? (:edges clj-society)))
    (is (= (count expected-route-edge-ids) (count (:edges clj-society))))
    (is (= 0.5 (get-in clj-society [:cb5t :E])))
    (is (= "weightedSum" (get-in clj-society [:characterRollup :type])))))
