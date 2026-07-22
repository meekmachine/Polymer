(ns polymer.emphatic-test
  (:require [cljs.test :refer [deftest is]]
            [polymer.core :as polymer]
            [polymer.emphatic.domain :as domain]))

(defn collect
  [target subscribe-fn]
  (let [events (atom [])]
    {:events events
     :unsubscribe (subscribe-fn target #(swap! events conj (js->clj % :keywordize-keys true)))}))

(defn domain-events [agency]
  (collect agency (fn [target listener] (.subscribeEvents ^js target listener))))

(defn scheduled-snippet-events [events]
  (keep #(when (= "animation.requestScheduleSnippet" (:type %)) %) @(:events events)))

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
    ((aget plan "onBoundary") #js {:word "Absolutely"
                                   :observedElapsedSec 0
                                   :hostElapsedSec 0})
    ((aget plan "onBoundary") #js {:word "not"
                                   :observedElapsedSec 0.2
                                   :hostElapsedSec 0.2})
    ((aget plan "onBoundary") #js {:word "the"
                                   :observedElapsedSec 0.4
                                   :hostElapsedSec 0.4})
    ((aget plan "onBoundary") #js {:word "answer"
                                   :observedElapsedSec 0.6
                                   :hostElapsedSec 0.6})
    #js {:stop (fn [] true)}))

(deftest emphatic-domain-marks-content-words
  (let [plan (domain/analyze-utterance "Absolutely not the answer!")]
    (is (true? (:exclamationEmphasis plan)))
    (is (seq (:emphasisWords plan)))
    (is (some #{0} (:emphasisWords plan))) ;; Absolutely
    (is (some #{1} (:emphasisWords plan))) ;; not
    (is (seq (:browGestures plan)))
    (is (seq (:headGestures plan)))))

(deftest emphatic-word-boundary-schedules-stress-gestures
  (let [agency (polymer/createEmphaticAgency nil)
        events (domain-events agency)]
    (.dispatch ^js agency #js {:type "speechStarted"
                               :name "tts:test"
                               :text "Absolutely not the answer!"})
    (.dispatch ^js agency #js {:type "wordBoundary" :word "Absolutely" :wordIndex 0})
    (let [requests (scheduled-snippet-events events)
          snapshot (js->clj (.snapshot ^js agency) :keywordize-keys true)]
      (is (seq requests))
      (is (every? #(= "emphatic" (:agency %)) requests))
      (is (some #{"raise" "furrow" "flash" "nod" "tilt" "turn"}
                (map #(get-in % [:snippet :metadata :gesture]) requests)))
      (is (>= (:scheduledCount snapshot) 1)))
    ((:unsubscribe events))
    (.dispose ^js agency)))

(deftest emphatic-skips-non-emphasis-words
  (let [agency (polymer/createEmphaticAgency nil)
        events (domain-events agency)]
    (.dispatch ^js agency #js {:type "speechStarted"
                               :name "tts:test"
                               :text "Absolutely not the answer!"})
    (.dispatch ^js agency #js {:type "wordBoundary" :word "the" :wordIndex 2})
    (is (empty? (scheduled-snippet-events events)))
    ((:unsubscribe events))
    (.dispose ^js agency)))

(deftest character-network-routes-tts-to-emphatic-animation
  (let [calls (atom [])
        system (polymer/createCharacterAgencies
                #js {:tts #js {:providers #js {:webSpeechSpeak (fake-web-speech-provider)}}
                     :animation #js {:runtime (fake-runtime calls)}})
        events (domain-events system)]
    (.dispatch ^js system #js {:agency "tts"
                               :command #js {:type "speak"
                                             :engine "webSpeech"
                                             :text "Absolutely not the answer!"
                                             :name "tts:test:emphatic"}})
    (let [event-types (map :type @(:events events))]
      (is (some #{"ttsWordBoundary"} event-types))
      (is (some #{"emphaticGestureScheduled"} event-types))
      (is (some #(= "emphatic" (get-in % [:options :sourceAgency])) @calls)))
    ((:unsubscribe events))
    (.dispose ^js system)))
