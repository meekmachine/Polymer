(ns polymer.blink-test
  (:require [cljs.test :refer [async deftest is testing]]
            [polymer.core :as polymer]))

(defn collect [target subscribe-fn]
  (let [events (atom [])]
    {:events events
     :unsubscribe (subscribe-fn target #(swap! events conj (js->clj % :keywordize-keys true)))}))

(defn effect-events [agency]
  (collect agency (fn [target listener] (.subscribeEffects ^js target listener))))

(defn domain-events [agency]
  (collect agency (fn [target listener] (.subscribeEvents ^js target listener))))

(deftest blink-state-clamps-config
  (let [agency (polymer/createBlinkAgency #js {:frequency 90
                                               :duration 2
                                               :burstFrequency 2
                                               :burstCount 22
                                               :burstGap 2})
        snapshot (js->clj (.snapshot ^js agency) :keywordize-keys true)]
    (is (= 60 (:frequency snapshot)))
    (is (= 1 (:duration snapshot)))
    (is (= 1 (:burstFrequency snapshot)))
    (is (= 8 (:burstCount snapshot)))
    (is (= 0.5 (:burstGap snapshot)))
    (.dispose ^js agency)))

(deftest manual-blink-emits-animation-request-and-state
  (let [agency (polymer/createBlinkAgency nil)
        events (domain-events agency)
        effects (effect-events agency)]
    (.triggerBlink ^js agency)
    (let [animation-request (some #(when (= "animation.requestScheduleSnippet" (:type %)) %) @(:events events))
          snapshot (js->clj (.snapshot ^js agency) :keywordize-keys true)]
      (is animation-request)
      (is (= "blink" (:agency animation-request)))
      (is (= 1 (get-in animation-request [:snippet :metadata :blinkCount])))
      (is (empty? @(:events effects)))
      (is (= 1 (:scheduledBlinkCount snapshot))))
    ((:unsubscribe events))
    ((:unsubscribe effects))
    (.dispose ^js agency)))

(deftest n-blink-burst-builds-one-longer-command
  (let [agency (polymer/createBlinkAgency #js {:duration 0.12 :burstGap 0.07})
        events (domain-events agency)]
    (.triggerBlink ^js agency #js {:burstCount 3})
    (let [request (some #(when (= "animation.requestScheduleSnippet" (:type %)) %) @(:events events))
          snippet (:snippet request)
          snapshot (js->clj (.snapshot ^js agency) :keywordize-keys true)]
      (is (= 3 (get-in snippet [:metadata :blinkCount])))
      (is (> (:maxTime snippet) (:duration snapshot)))
      (is (= 3 (:scheduledBlinkCount snapshot)))
      (is (= 1 (:scheduledBurstCount snapshot))))
    ((:unsubscribe events))
    (.dispose ^js agency)))

(deftest fast-blink-emits-cross-agency-signal
  (let [agency (polymer/createBlinkAgency #js {:frequency 45})
        events (domain-events agency)]
    (.triggerBlink ^js agency)
    (is (some #(and (= "signal" (:type %))
                    (= "blink-fast" (:signal %)))
              @(:events events)))
    ((:unsubscribe events))
    (.dispose ^js agency)))

(deftest character-system-forwards-agency-streams
  (let [system (polymer/createCharacterAgencies nil)
        events (domain-events system)
        effects (effect-events system)]
    (.dispatch ^js system #js {:agency "blink" :command #js {:type "triggerBlink"}})
    (is (some #(= "blinkPlanned" (:type %)) @(:events events)))
    (is (some #(= "animation.requestScheduleSnippet" (:type %)) @(:events events)))
    (is (= "animation.scheduleSnippet" (:type (first @(:events effects)))))
    (is (= "animation" (:agency (first @(:events effects)))))
    (is (= "blink" (:sourceAgency (first @(:events effects)))))
    ((:unsubscribe events))
    ((:unsubscribe effects))
    (.dispose ^js system)))

(deftest disposed-agency-stops-events
  (let [agency (polymer/createBlinkAgency nil)
        effects (effect-events agency)]
    (.dispose ^js agency)
    (.triggerBlink ^js agency)
    (is (empty? @(:events effects)))))

(deftest automatic-blink-uses-enabled-timer
  (async done
    (let [agency (polymer/createBlinkAgency #js {:frequency 60 :randomness 0})
          events (domain-events agency)]
      (.enable ^js agency)
      (js/setTimeout
       (fn []
         (try
           (is (some #(= "animation.requestScheduleSnippet" (:type %)) @(:events events)))
           ((:unsubscribe events))
           (.dispose ^js agency)
           (done)
           (catch :default error
             (.dispose ^js agency)
             (throw error))))
       1150))))
