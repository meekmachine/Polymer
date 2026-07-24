(ns polymer.prosodic-test
  (:require [cljs.test :refer [deftest is]]
            [polymer.core :as polymer]))

(defn collect
  "Collect public stream events from an agency or character network."
  [target subscribe-fn]
  (let [events (atom [])]
    {:events events
     :unsubscribe (subscribe-fn target #(swap! events conj (js->clj % :keywordize-keys true)))}))

(defn domain-events [agency]
  (collect agency (fn [target listener] (.subscribeEvents ^js target listener))))

(defn effect-events [agency]
  (collect agency (fn [target listener] (.subscribeEffects ^js target listener))))

(defn scheduled-snippet-events [events]
  (keep #(when (= "animation.requestScheduleSnippet" (:type %)) %) @(:events events)))

(defn removed-snippet-events [events]
  (keep #(when (= "animation.requestRemoveSnippet" (:type %)) %) @(:events events)))

(defn fake-runtime [calls]
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

(defn fake-web-speech-provider []
  (fn [plan]
    ((aget plan "onAudioStarted") #js {:currentTimeSec 0})
    ((aget plan "onBoundary") #js {:word "hello"
                                   :observedElapsedSec 0
                                   :hostElapsedSec 0})
    #js {:stop (fn [] true)}))

(deftest prosodic-word-boundary-schedules-gesture-request
  (let [agency (polymer/createProsodicAgency #js {:openingGesture false})
        events (domain-events agency)
        effects (effect-events agency)]
    (.dispatch ^js agency #js {:type "speechStarted" :name "tts:test"})
    (.dispatch ^js agency #js {:type "wordBoundary" :word "hello" :wordIndex 0})
    (let [request (first (scheduled-snippet-events events))
          snippet (:snippet request)
          snapshot (js->clj (.snapshot ^js agency) :keywordize-keys true)]
      (is request)
      (is (= "prosodic" (:agency request)))
      (is (= "emphasis" (get-in snippet [:metadata :gesture])))
      (is (contains? (:curves snippet) :1))
      (is (contains? (:curves snippet) :2))
      (is (contains? (:curves snippet) :55))
      (is (= 1 (:scheduledCount snapshot)))
      (is (empty? @(:events effects))))
    ((:unsubscribe events))
    ((:unsubscribe effects))
    (.dispose ^js agency)))

(deftest prosodic-word-boundary-can-record-without-gesture
  (let [agency (polymer/createProsodicAgency #js {:browPulseEvery 3
                                                  :headPulseEvery 3
                                                  :openingGesture false})
        events (domain-events agency)]
    (.dispatch ^js agency #js {:type "speechStarted" :name "tts:test"})
    ;; With every-3 cadence, index 1 is neither brow nor head pulse.
    (.dispatch ^js agency #js {:type "wordBoundary" :word "there" :wordIndex 1})
    (let [snapshot (js->clj (.snapshot ^js agency) :keywordize-keys true)]
      (is (some #(= "prosodicWordBoundary" (:type %)) @(:events events)))
      (is (empty? (scheduled-snippet-events events)))
      (is (= "there" (:currentWord snapshot)))
      (is (= 0 (:scheduledCount snapshot))))
    ((:unsubscribe events))
    (.dispose ^js agency)))

(deftest prosodic-compat-start-talking-aliases-work
  (let [agency (polymer/createProsodicAgency #js {:openingGesture false})
        events (domain-events agency)]
    (.startTalking ^js agency)
    (.pulse ^js agency "hello" 0)
    (let [request (first (scheduled-snippet-events events))]
      (is request)
      (is (= "emphasis" (get-in request [:snippet :metadata :gesture])))
      (.stopTalking ^js agency)
      (is (some #(= "animation.requestRemoveSnippet" (:type %)) @(:events events))))
    ((:unsubscribe events))
    (.dispose ^js agency)))

(deftest prosodic-keeps-cadence-after-request-response
  (let [agency (polymer/createProsodicAgency #js {:openingGesture false})
        events (domain-events agency)]
    (.dispatch ^js agency #js {:type "conversation.requestResponse" :text "hi"})
    (.dispatch ^js agency #js {:type "speechStarted" :name "tts:test"})
    (.dispatch ^js agency #js {:type "wordBoundary" :word "hello" :wordIndex 0})
    (let [request (first (scheduled-snippet-events events))]
      (is request)
      (is (= "emphasis" (get-in request [:snippet :metadata :gesture]))))
    ((:unsubscribe events))
    (.dispose ^js agency)))

(deftest prosodic-stop-removes-active-gesture-requests
  (let [agency (polymer/createProsodicAgency #js {:openingGesture false})
        events (domain-events agency)]
    (.dispatch ^js agency #js {:type "speechStarted" :name "tts:test"})
    (.dispatch ^js agency #js {:type "wordBoundary" :word "hello" :wordIndex 0})
    (let [scheduled (first (scheduled-snippet-events events))]
      (.dispatch ^js agency #js {:type "stop"})
      (let [removed (first (removed-snippet-events events))
            snapshot (js->clj (.snapshot ^js agency) :keywordize-keys true)]
        (is (= (get-in scheduled [:snippet :name]) (:name removed)))
        (is (empty? (:activeSnippets snapshot)))
        (is (= 1 (:removedCount snapshot)))))
    ((:unsubscribe events))
    (.dispose ^js agency)))

(deftest character-network-routes-tts-word-boundaries-to-prosodic-animation
  (let [calls (atom [])
        system (polymer/createCharacterAgencies
                #js {:tts #js {:providers #js {:webSpeechSpeak (fake-web-speech-provider)}}
                     :animation #js {:runtime (fake-runtime calls)}})
        events (domain-events system)]
    (.dispatch ^js system #js {:agency "tts"
                               :command #js {:type "speak"
                                             :engine "webSpeech"
                                             :text "hello world"
                                             :name "tts:test:prosodic"}})
    (let [event-types (map :type @(:events events))]
      (is (some #{"ttsWordBoundary"} event-types))
      (is (some #{"prosodicGestureScheduled"} event-types))
      (is (some #(= "prosodic" (get-in % [:options :sourceAgency])) @calls))
      (is (some #(contains? (:curves %) :55) @calls)))
    ((:unsubscribe events))
    (.dispose ^js system)))
