(ns polymer.character-network-test
  (:require [cljs.test :refer [deftest is]]
            [polymer.core :as polymer]))

(defn collect-events
  [system]
  (let [events (atom [])]
    {:events events
     :unsubscribe (.subscribeEvents ^js system
                                    #(swap! events conj (js->clj % :keywordize-keys true)))}))

(defn fake-web-speech-provider
  []
  (fn [plan]
    ((aget plan "onAudioStarted") #js {:currentTimeSec 0})
    #js {:stop (fn [] true)}))

(deftest public-factories-include-the-foundation-agencies
  (let [conversation (polymer/createConversationAgency nil)
        transcription (polymer/createTranscriptionAgency nil)
        hair (polymer/createHairAgency nil)
        camera-context (polymer/createCameraContextAgency nil)]
    (is (= "conversation" (:agency (js->clj (.snapshot ^js conversation) :keywordize-keys true))))
    (is (= "transcription" (:agency (js->clj (.snapshot ^js transcription) :keywordize-keys true))))
    (is (= "hair" (:agency (js->clj (.snapshot ^js hair) :keywordize-keys true))))
    (is (= "cameraContext" (:agency (js->clj (.snapshot ^js camera-context) :keywordize-keys true))))
    (.dispose ^js conversation)
    (.dispose ^js transcription)
    (.dispose ^js hair)
    (.dispose ^js camera-context)))

(deftest character-network-exposes-foundation-agencies-in-snapshot-and-lookup
  (let [system (polymer/createCharacterAgencies nil)
        snapshot (js->clj (.snapshot ^js system) :keywordize-keys true)]
    (is (= "conversation" (get-in snapshot [:conversation :agency])))
    (is (= "transcription" (get-in snapshot [:transcription :agency])))
    (is (= "hair" (get-in snapshot [:hair :agency])))
    (is (= "cameraContext" (get-in snapshot [:cameraContext :agency])))
    (is (.agency ^js system "conversation"))
    (is (.agency ^js system "transcription"))
    (is (.agency ^js system "hair"))
    (is (.agency ^js system "cameraContext"))
    (.dispose ^js system)))

(deftest character-network-routes-camera-context-to-gaze
  (let [system (polymer/createCharacterAgencies #js {:cameraContext #js {:coalesceMs 0}
                                                    :gaze #js {:smoothFactor 1
                                                               :coalesceMs 0}})
        events (collect-events system)]
    (.dispatch ^js system #js {:agency "gaze"
                               :command #js {:type "setTarget"
                                             :target #js {:x 0.2 :y 0.1}}})
    (.dispatch ^js system #js {:agency "cameraContext"
                               :command #js {:type "updateCamera"
                                             :cameraPosition #js {:x 1 :y 0 :z 2}
                                             :targetPosition #js {:x 0 :y 0 :z 0}
                                             :modelQuaternion #js {:x 0 :y 0 :z 0 :w 1}}})
    (let [with-camera (js->clj (.snapshot ^js system) :keywordize-keys true)]
      (is (some #(= "camera.fact" (:type %)) @(:events events)))
      (is (some #(= "eyeHeadTracking.requestGaze" (:type %)) @(:events events)))
      (is (= 0.2 (get-in with-camera [:gaze :baseTarget :x])))
      (is (not= 0 (get-in with-camera [:gaze :cameraRelativeOffset :x]))))
    (.dispatch ^js system #js {:agency "cameraContext"
                               :command #js {:type "invalidateStale"
                                             :reason "test"}})
    (let [stale (js->clj (.snapshot ^js system) :keywordize-keys true)]
      (is (= 0 (get-in stale [:gaze :cameraRelativeOffset :x])))
      (is (= 0.2 (get-in stale [:gaze :baseTarget :x]))))
    ((:unsubscribe events))
    (.dispose ^js system)))

(deftest character-network-routes-transcription-to-conversation
  (let [system (polymer/createCharacterAgencies nil)
        events (collect-events system)]
    (.dispatch ^js system #js {:agency "conversation"
                               :command #js {:type "start"}})
    (.dispatch ^js system #js {:agency "transcription"
                               :command #js {:type "start"}})
    (.dispatch ^js system #js {:agency "transcription"
                               :command #js {:type "providerFinal"
                                             :text "hello character"
                                             :confidence 0.95}})
    (let [snapshot (js->clj (.snapshot ^js system) :keywordize-keys true)]
      (is (some #(= "transcription.final" (:type %)) @(:events events)))
      (is (some #(and (= "conversation.userUtterance" (:type %))
                      (= "hello character" (:text %)))
                @(:events events)))
      (is (some #(= "conversation.requestResponse" (:type %))
                @(:events events)))
      (is (= 1 (get-in snapshot [:conversation :userUtteranceCount]))))
    ((:unsubscribe events))
    (.dispose ^js system)))

(deftest character-network-routes-conversation-to-tts-and-tts-feedback-back
  (let [system (polymer/createCharacterAgencies
                #js {:tts #js {:providers #js {:webSpeechSpeak (fake-web-speech-provider)}}})
        events (collect-events system)]
    (.dispatch ^js system #js {:agency "conversation"
                               :command #js {:type "start"}})
    (.dispatch ^js system #js {:agency "conversation"
                               :command #js {:type "transcriptFinal"
                                             :text "user question"}})
    (let [request (some #(when (= "conversation.requestResponse" (:type %)) %)
                        @(:events events))]
      (.dispatch ^js system #js {:agency "conversation"
                                 :command #js {:type "responseReady"
                                               :requestId (:requestId request)
                                               :turnId (:turnId request)
                                               :text "agent answer"}}))
    (let [snapshot (js->clj (.snapshot ^js system) :keywordize-keys true)]
      (is (some #(and (= "tts.requestSpeak" (:type %))
                      (= "agent answer" (:text %)))
                @(:events events)))
      (is (some #(= "ttsSpeechStarted" (:type %))
                @(:events events)))
      (is (some #(and (= "transcription.ttsStatus" (:type %))
                      (= "speaking" (:status %)))
                @(:events events)))
      (is (= "speaking" (get-in snapshot [:transcription :agentSpeechStatus]))))
    ((:unsubscribe events))
    (.dispose ^js system)))

(deftest character-network-routes-hair-events-without-host-message-bus
  (let [system (polymer/createCharacterAgencies nil)
        events (collect-events system)]
    (.dispatch ^js system #js {:agency "hair"
                               :command #js {:type "setBaseColor"
                                             :value "#abcdef"}})
    (let [snapshot (js->clj (.snapshot ^js system) :keywordize-keys true)]
      (is (= "#abcdef" (get-in snapshot [:hair :hairColor :baseColor])))
      (is (some #(and (= "hair.requestRuntime" (:type %))
                      (= "applyState" (:action %))
                      (:requestId %))
                @(:events events))))
    ((:unsubscribe events))
    (.dispose ^js system)))
