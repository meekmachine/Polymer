(ns polymer.tts-test
  (:require [cljs.test :refer [async deftest is testing]]
            [polymer.core :as polymer]
            [polymer.lipsync.state :as lipsync-state]
            [polymer.tts.azure :as azure]
            [polymer.tts.planner :as planner]
            [polymer.tts.runtime :as runtime]
            [polymer.tts.state :as state]))

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

(defn deferred
  "Create a promise plus resolve/reject callbacks for async provider tests."
  []
  (let [resolve-ref (atom nil)
        reject-ref (atom nil)
        promise (js/Promise.
                 (fn [resolve reject]
                   (reset! resolve-ref resolve)
                   (reset! reject-ref reject)))]
    {:promise promise
     :resolve (fn [value] (@resolve-ref value))
     :reject (fn [error] (@reject-ref error))}))

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

(defn fake-engine
  "Create an Embody-shaped engine spy for production-shaped routing tests."
  [calls]
  #js {:playSnippet
       (fn [snippet options]
         (swap! calls conj {:method "engine.playSnippet"
                            :name (aget snippet "name")
                            :curves (js->clj (aget snippet "curves") :keywordize-keys true)
                            :options (js->clj options :keywordize-keys true)})
         #js {:clipName (aget snippet "name")
              :stop (fn [] true)
              :finished (js/Promise.resolve nil)})
       :playTypedSnippet
       (fn [snippet options]
         (swap! calls conj {:method "engine.playTypedSnippet"
                            :name (aget snippet "name")
                            :channels (js->clj (aget snippet "channels") :keywordize-keys true)
                            :options (js->clj options :keywordize-keys true)})
         #js {:clipName (aget snippet "name")
              :stop (fn [] true)
              :finished (js/Promise.resolve nil)})
       :cleanupSnippet
       (fn [name]
         (swap! calls conj {:method "engine.cleanupSnippet" :name name})
         true)})

(deftest provider-planner-plans-provider-specific-speech
  (testing "web speech gets a speech plan with no Azure synthesis step"
    (let [plan (planner/plan-speech {:type "speak"
                                     :engine "webSpeech"
                                     :text "hello"}
                                    {:engine "webSpeech"}
                                    {:hasWebSpeech true})]
      (is (:ok plan))
      (is (= ["configure-lipsync" "speak-web-speech" "use-web-speech-boundaries"]
             (map :op (:steps plan))))))
  (testing "azure gets synthesis and audio playback steps"
    (let [plan (planner/plan-speech {:type "speak"
                                     :engine "azure"
                                     :text "hello"}
                                    {:engine "azure"}
                                    {:backendUrl "http://backend"})]
      (is (:ok plan))
      (is (= ["configure-lipsync" "synthesize-azure" "play-azure-audio" "emit-word-boundaries"]
             (map :op (:steps plan))))))
  (testing "voice loading fails before side effects when the provider is missing"
    (let [plan (planner/plan-voice-load "azure" {})]
      (is (false? (:ok plan)))
      (is (= [{:op "fail" :reason "provider-not-ready" :engine "azure"}]
             (:steps plan)))))
  (testing "injected Azure providers satisfy provider planning without exposing keys to the browser"
    (let [plan (planner/plan-speech {:type "speak"
                                     :engine "azure"
                                     :text "hello"}
                                    {:engine "azure"}
                                    {:hasAzureSynthesize true})]
      (is (:ok plan)))))

(deftest tts-command-planner-makes-session-interruption-explicit
  (let [idle-state {:status "idle" :config {:engine "webSpeech"}}
        active-state {:status "speaking" :config {:engine "webSpeech"}}
        speak-command {:type "speak" :text "hello"}
        load-command {:type "loadVoices"}
        reset-command {:type "reset"}]
    (is (= ["speak"]
           (map :op (planner/plan-command idle-state speak-command))))
    (is (= ["stop-active-session" "speak"]
           (map :op (planner/plan-command active-state speak-command))))
    (is (= [{:op "load-voices" :engine "webSpeech"}]
           (planner/plan-command idle-state load-command)))
    (is (= ["advance-session" "cancel-voice-loads" "stop-active-session" "reset"]
           (map :op (planner/plan-command active-state reset-command))))))

(deftest azure-config-ignores-blank-voice-and-backend-overrides
  (testing "blank strings do not wipe Azure voice defaults"
    (let [normalized (state/normalize-config {:azureVoiceName ""
                                              :backendUrl ""
                                              :voiceName ""})]
      (is (= "en-US-JennyNeural" (:azureVoiceName normalized)))
      (is (= "" (:backendUrl normalized)))
      (is (= "" (:voiceName normalized)))))
  (testing "azure-voice-name falls back when command voice is blank"
    (is (= "en-US-JennyNeural"
           (runtime/azure-voice-name {:azureVoiceName ""} {:voiceName ""})))
    (is (= "en-US-AriaNeural"
           (runtime/azure-voice-name {:azureVoiceName "en-US-AriaNeural"}
                                     {:voiceName ""}))))
  (testing "pitch mapping matches Latticework (* 50)"
    (is (= "0%" (runtime/scalar->azure-pitch-percent 1)))
    (is (= "+25%" (runtime/scalar->azure-pitch-percent 1.5)))
    (is (= "-25%" (runtime/scalar->azure-pitch-percent 0.5)))))

(deftest azure-provider-helpers-keep-provider-fields-and-build-lipsync-command
  (let [visemes (azure/normalize-provider-visemes [{:viseme_id 3 :audio_offset 0.2}
                                                   {:visemeId 1 :audioOffset 0.1}
                                                   {:id 2 :time 0.15}])
        words (lipsync-state/normalize-word-timings [{:word "hi" :start_time 0 :end_time 0.2}
                                                     {:word "" :start_time 0.3 :end_time 0.4}])
        synthesis (azure/normalize-azure-synthesis {:audioBase64 "ZmFrZQ=="
                                                    :audioFormat "audio/mpeg"
                                                    :durationSec 0.42
                                                    :visemes [{:viseme_id 2 :audio_offset 0.1}]
                                                    :wordTimings [{:word "there" :startSec 0.1 :endSec 0.4}]})
        command (azure/azure-synthesis->lipsync-command
                 "tts:test"
                 "there"
                 synthesis
                 {:visualLeadMs 35})]
    (is (= [1 2 3] (map :visemeId visemes)))
    (is (= [0.1 0.15 0.2] (map :time visemes)))
    (is (= [{:word "hi" :startSec 0 :endSec 0.2}] words))
    (is (= 0.42 (:durationSec synthesis)))
    (is (= [{:viseme_id 2 :audio_offset 0.1}] (:visemes synthesis)))
    (is (= {:type "processAzureVisemes"
            :name "tts:test"
            :text "there"
            :source "azure"
            :visemes [{:viseme_id 2 :audio_offset 0.1}]
            :wordTimings [{:word "there" :startSec 0.1 :endSec 0.4}]
            :totalDurationMs 420
            :options {:visualLeadMs 35}}
           command))
    (is (= [{:visemeId 2 :time 0.1}]
           (azure/normalize-provider-visemes (:visemes command))))
    (is (= [{:word "there" :startSec 0.1 :endSec 0.4}]
           (lipsync-state/normalize-word-timings (:wordTimings command))))))

(deftest tts-agency-gates-provider-side-effects-with-provider-plan
  (let [agency (polymer/createTTSAgency)
        events (domain-events agency)]
    (.dispatch ^js agency #js {:type "loadVoices" :engine "azure"})
    (let [plan-event (some #(when (= "ttsPlanCreated" (:type %)) %) @(:events events))
          error-event (some #(when (= "error" (:type %)) %) @(:events events))
          snapshot (js->clj (.snapshot ^js agency) :keywordize-keys true)]
      (is plan-event)
      (is (false? (get-in plan-event [:plan :ok])))
      (is (= "provider-not-ready" (get-in plan-event [:plan :steps 0 :reason])))
      (is (= "error" (:status snapshot)))
      (is (= "error" (:azureStatus snapshot)))
      (is (= "TTS plan failed: provider not ready (azure)" (:message error-event))))
    ((:unsubscribe events))
    (.dispose ^js agency)))

(deftest tts-agency-owns-web-speech-session-and-emits-lipsync-facts
  (let [agency (polymer/createTTSAgency #js {:tongueScale 1.35
                                             :providers #js {:webSpeechSpeak (fake-web-speech-provider)}})
        events (domain-events agency)]
    (.dispatch ^js agency #js {:type "speak"
                               :engine "webSpeech"
                               :text "hello world"
                               :name "tts:test:web"})
    (let [event-types (map :type @(:events events))
          commands (keep #(when (= "lipSync.command" (:type %)) (:command %)) @(:events events))
          snapshot (js->clj (.snapshot ^js agency) :keywordize-keys true)
          queue (js->clj (.schedulerQueue ^js agency) :keywordize-keys true)]
      (is (some #{"ttsPlanCreated"} event-types))
      (is (some #{"ttsSpeechStarted"} event-types))
      (is (= ["configure" "startText" "audioStarted" "wordBoundary"]
             (map :type commands)))
      (is (= ["ttsSessionStarted"] (map :type queue)))
      (is (= 1.35 (get-in (first commands) [:config :tongueScale])))
      (is (= 0.35 (get-in (first commands) [:config :wordDriftThresholdSec])))
      (is (= "speaking" (:status snapshot)))
      (is (:speaking snapshot))
      (.stop ^js agency)
      (let [stop-queue (js->clj (.schedulerQueue ^js agency) :keywordize-keys true)]
        (is (= ["ttsSessionStarted" "ttsSessionStopped"] (map :type stop-queue)))
        (is (= "requested" (:reason (second stop-queue))))))
    ((:unsubscribe events))
    (.dispose ^js agency)))

(deftest tts-agency-stops-late-web-speech-handle-after-session-stop
  (async done
         (let [speech (deferred)
               stop-count (atom 0)
               agency (polymer/createTTSAgency
                       #js {:providers #js {:webSpeechSpeak
                                            (fn [_plan] (:promise speech))}})]
           (.dispatch ^js agency #js {:type "speak"
                                      :engine "webSpeech"
                                      :text "hello"
                                      :name "tts:test:late-webspeech"})
           (.stop ^js agency)
           ((:resolve speech) #js {:stop (fn [] (swap! stop-count inc))})
           (js/setTimeout
            (fn []
              (try
                (is (= 1 @stop-count))
                (.dispose ^js agency)
                (done)
                (catch :default error
                  (.dispose ^js agency)
                  (throw error))))
            20))))

(deftest tts-agency-reset-stops-active-web-speech-handle
  (async done
         (let [stop-count (atom 0)
               agency (polymer/createTTSAgency
                       #js {:providers #js {:webSpeechSpeak
                                            (fn [plan]
                                              ((aget plan "onAudioStarted") #js {:currentTimeSec 0})
                                              #js {:stop (fn [] (swap! stop-count inc))})}})]
           (.dispatch ^js agency #js {:type "speak"
                                      :engine "webSpeech"
                                      :text "hello"
                                      :name "tts:test:reset-webspeech"})
           (js/setTimeout
            (fn []
              (.reset ^js agency)
              (js/setTimeout
               (fn []
                 (try
                   (is (= 1 @stop-count))
                   (.dispose ^js agency)
                   (done)
                   (catch :default error
                     (.dispose ^js agency)
                     (throw error))))
               10))
            10))))

(deftest tts-agency-ignores-stale-voice-load-results
  (async done
         (let [requests (atom [])
               agency (polymer/createTTSAgency
                       #js {:providers #js {:webSpeechVoices
                                            (fn []
                                              (let [request (deferred)]
                                                (swap! requests conj request)
                                                (:promise request)))}})]
           (.loadVoices ^js agency "webSpeech")
           (.loadVoices ^js agency "webSpeech")
           ((:resolve (second @requests)) #js [#js {:name "new voice"
                                                    :lang "en-US"}])
           ((:resolve (first @requests)) #js [#js {:name "old voice"
                                                   :lang "en-US"}])
           (js/setTimeout
            (fn []
              (try
                (let [snapshot (js->clj (.snapshot ^js agency) :keywordize-keys true)]
                  (is (= ["new voice"] (map :id (:webSpeechVoices snapshot)))))
                (.dispose ^js agency)
                (done)
                (catch :default error
                  (.dispose ^js agency)
                  (throw error))))
            20))))

(deftest character-network-routes-tts-to-lipsync-and-animation
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
      (is (some #{"lipSyncTimelineStarted"} event-types))
      (is (some #{"animationSnippetScheduled"} event-types))
      (is (= "playSnippet" (:method (first @calls)))))
    ((:unsubscribe events))
    (.dispose ^js system)))

(deftest character-network-routes-web-speech-lipsync-through-embody-typed-engine
  (let [calls (atom [])
        system (polymer/createCharacterAgencies
                #js {:tts #js {:providers #js {:webSpeechSpeak (fake-web-speech-provider)}}
                     :animation #js {:engine (fake-engine calls)}})
        events (domain-events system)]
    (.dispatch ^js system #js {:agency "tts"
                               :command #js {:type "speak"
                                             :engine "webSpeech"
                                             :text "hello world"
                                             :name "tts:test:typed-webspeech"}})
    (let [event-types (map :type @(:events events))
          first-call (first @calls)]
      (is (some #{"ttsSpeechStarted"} event-types))
      (is (some #{"lipSyncTimelineStarted"} event-types))
      (is (some #{"animationSnippetScheduled"} event-types))
      (is (= "engine.playTypedSnippet" (:method first-call)))
      (is (some #(and (= "viseme" (get-in % [:target :type]))
                      (= 1 (get-in % [:target :id])))
                (:channels first-call)))
      (is (some #(and (= "bone" (get-in % [:target :type]))
                      (= "JAW" (get-in % [:target :id]))
                      (= "rz" (get-in % [:target :channel])))
                (:channels first-call)))
      (is (not (contains? (:options first-call) :snippetCategory)))
      (is (false? (get-in first-call [:options :autoVisemeJaw]))))
    ((:unsubscribe events))
    (.dispose ^js system)))

(deftest tts-agency-emits-raw-azure-facts-for-lipsync-normalization
  (async done
         (let [agency (polymer/createTTSAgency
                       #js {:providers #js {:azureSynthesize
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
                      process-command (some #(when (= "processAzureVisemes" (:type %)) %) commands)
                      queue (js->clj (.schedulerQueue ^js agency) :keywordize-keys true)]
                  (is process-command)
                  (is (= [{:viseme_id 2 :audio_offset 0.1}] (:visemes process-command)))
                  (is (= [{:word "hello" :start_time 0 :end_time 0.3}] (:wordTimings process-command)))
                  (is (= ["ttsSessionStarted" "audioBoundaryPolling"] (map :type queue)))
                  (is (= [0 1] (map :queueIndex queue)))
                  (is (= 1 (:wordCount (second queue)))))
                ((:unsubscribe events))
                (.dispose ^js agency)
                (done)
                (catch :default error
                  (.dispose ^js agency)
                  (throw error))))
            20))))
