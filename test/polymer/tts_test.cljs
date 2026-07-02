(ns polymer.tts-test
  (:require [cljs.test :refer [async deftest is testing]]
            [polymer.core :as polymer]
            [polymer.tts.goap :as goap]
            [polymer.tts.transducers :as transducers]))

;; These tests keep the TTS provider boundary honest.
;; Provider side effects are injected here; production defaults live in Polymer.

(defn collect
  "Collect public stream events from an agency or character network."
  [target subscribe-fn]
  (let [events (atom [])]
    {:events events
     :unsubscribe (subscribe-fn target #(swap! events conj (js->clj % :keywordize-keys true)))}))

(defn domain-events
  "Subscribe to the agency/domain event stream."
  [agency]
  (collect agency (fn [target listener] (.subscribeEvents ^js target listener))))

(defn fake-web-speech-provider
  "Create a Web Speech provider that starts immediately and never ends itself."
  []
  (fn [plan]
    ((aget plan "onAudioStarted") #js {:currentTimeSec 0})
    ((aget plan "onBoundary") #js {:word "hello"
                                   :observedElapsedSec 0
                                   :hostElapsedSec 0})
    #js {:stop (fn [] true)}))

(defn fake-runtime
  "Create an animation runtime spy for character-network routing tests."
  [calls]
  #js {:playSnippet
       (fn [name curves options]
         (swap! calls conj {:method "playSnippet"
                            :name name
                            :curves (js->clj curves :keywordize-keys true)
                            :options (js->clj options :keywordize-keys true)})
         #js {:stop (fn [] true)
              :finished (js/Promise.resolve nil)})
       :cleanupSnippet
       (fn [name]
         (swap! calls conj {:method "cleanupSnippet" :name name})
         true)})

(deftest goap-plans-provider-specific-speech
  (testing "web speech gets a speech plan with no Azure synthesis step"
    (let [plan (goap/plan-speech {:type "speak"
                                  :engine "webSpeech"
                                  :text "hello"}
                                 {:engine "webSpeech"}
                                 {:hasWebSpeech true})]
      (is (:ok plan))
      (is (= ["configure-lipsync" "speak-web-speech" "use-web-speech-boundaries"]
             (map :op (:steps plan))))))
  (testing "azure gets synthesis and audio playback steps"
    (let [plan (goap/plan-speech {:type "speak"
                                  :engine "azure"
                                  :text "hello"}
                                 {:engine "azure"}
                                 {:backendUrl "http://backend"})]
      (is (:ok plan))
      (is (= ["configure-lipsync" "synthesize-azure" "play-azure-audio" "emit-word-boundaries"]
             (map :op (:steps plan)))))))

(deftest transducers-normalize-provider-payloads
  (let [visemes (transducers/normalize-azure-visemes [{:viseme_id 3 :audio_offset 0.2}
                                                      {:visemeId 1 :audioOffset 0.1}
                                                      {:id 2 :time 0.15}])
        words (transducers/normalize-word-boundaries [{:word "hi" :start_time 0 :end_time 0.2}
                                                      {:word "" :start_time 0.3 :end_time 0.4}])]
    (is (= [1 2 3] (map :id visemes)))
    (is (= [0.1 0.15 0.2] (map :time visemes)))
    (is (= [{:word "hi" :startSec 0 :endSec 0.2}] words))))

(deftest tts-agency-owns-web-speech-session-and-emits-lipsync-facts
  (let [agency (polymer/createTTSAgency #js {:providers #js {:webSpeechSpeak (fake-web-speech-provider)}})
        events (domain-events agency)]
    (.dispatch ^js agency #js {:type "speak"
                               :engine "webSpeech"
                               :text "hello world"
                               :name "tts:test:web"})
    (let [event-types (map :type @(:events events))
          commands (keep #(when (= "lipSync.command" (:type %)) (:command %)) @(:events events))
          snapshot (js->clj (.snapshot ^js agency) :keywordize-keys true)]
      (is (some #{"ttsPlanCreated"} event-types))
      (is (some #{"ttsSpeechStarted"} event-types))
      (is (= ["configure" "startText" "audioStarted" "wordBoundary"]
             (map :type commands)))
      (is (= "speaking" (:status snapshot)))
      (is (:speaking snapshot)))
    ((:unsubscribe events))
    (.dispose ^js agency)))

(deftest character-network-routes-tts-to-vocal-and-animation
  (let [calls (atom [])
        system (polymer/createCharacterAgencies
                #js {:tts #js {:providers #js {:webSpeechSpeak (fake-web-speech-provider)}}
                     :animation #js {:runtime (fake-runtime calls)}})
        events (domain-events system)]
    (.dispatch ^js system #js {:agency "tts"
                               :command #js {:type "speak"
                                             :engine "webSpeech"
                                             :text "hello world"
                                             :name "tts:test:character"}})
    (let [event-types (map :type @(:events events))]
      (is (some #{"ttsSpeechStarted"} event-types))
      (is (some #{"vocalTimelineStarted"} event-types))
      (is (some #{"animationSnippetScheduled"} event-types))
      (is (= "playSnippet" (:method (first @calls)))))
    ((:unsubscribe events))
    (.dispose ^js system)))

(deftest tts-agency-normalizes-azure-before-lipsync
  (async done
    (let [agency (polymer/createTTSAgency
                  #js {:backendUrl "http://backend"
                       :providers #js {:azureSynthesize
                                       (fn [_command]
                                         (js/Promise.resolve
                                          #js {:audio_base64 "ZmFrZQ=="
                                               :audio_format "audio/mpeg"
                                               :duration 0.4
                                               :visemes #js [#js {:viseme_id 2 :audio_offset 0.1}]
                                               :word_boundaries #js [#js {:word "hello"
                                                                          :start_time 0
                                                                          :end_time 0.3}]}))
                                       :azurePlayAudio
                                       (fn [_plan]
                                         (js/Promise.resolve
                                          #js {:durationSec 0.4
                                               :clock #js {:currentTime (fn [] 0)
                                                           :shouldContinue (fn [] false)}}))}})
          events (domain-events agency)]
      (.dispatch ^js agency #js {:type "speak"
                                 :engine "azure"
                                 :text "hello"
                                 :name "tts:test:azure"})
      (js/setTimeout
       (fn []
         (try
           (let [commands (keep #(when (= "lipSync.command" (:type %)) (:command %)) @(:events events))
                 process-command (some #(when (= "processAzureVisemes" (:type %)) %) commands)]
             (is process-command)
             (is (= [{:id 2 :time 0.1}] (:visemes process-command)))
             (is (= [{:word "hello" :startSec 0 :endSec 0.3}] (:wordTimings process-command))))
           ((:unsubscribe events))
           (.dispose ^js agency)
           (done)
           (catch :default error
             (.dispose ^js agency)
             (throw error))))
       20))))
