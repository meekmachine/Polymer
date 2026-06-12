(ns polymer.blink-test
  (:require [cljs.test :refer [async deftest is testing]]
            [polymer.core :as polymer]))

(defn collect [target subscribe-fn]
  (let [events (atom [])]
    {:events events
     :unsubscribe (subscribe-fn target #(swap! events conj (js->clj % :keywordize-keys true)))}))

(defn command-events [agency]
  (collect agency (fn [target listener] (.subscribeCommands ^js target listener))))

(defn status-events [agency]
  (collect agency (fn [target listener] (.subscribeStatus ^js target listener))))

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

(deftest manual-blink-emits-command-and-state
  (let [agency (polymer/createBlinkAgency nil)
        commands (command-events agency)]
    (.triggerBlink ^js agency)
    (let [events @(:events commands)
          command (first events)
          snapshot (js->clj (.snapshot ^js agency) :keywordize-keys true)]
      (is (= 1 (count events)))
      (is (= "scheduleSnippet" (:type command)))
      (is (= "blink" (:agency command)))
      (is (= 1 (get-in command [:snippet :metadata :blinkCount])))
      (is (= 1 (:scheduledBlinkCount snapshot))))
    ((:unsubscribe commands))
    (.dispose ^js agency)))

(deftest n-blink-burst-builds-one-longer-command
  (let [agency (polymer/createBlinkAgency #js {:duration 0.12 :burstGap 0.07})
        commands (command-events agency)]
    (.triggerBlink ^js agency #js {:burstCount 3})
    (let [command (first @(:events commands))
          snippet (:snippet command)
          snapshot (js->clj (.snapshot ^js agency) :keywordize-keys true)]
      (is (= 3 (get-in snippet [:metadata :blinkCount])))
      (is (> (:maxTime snippet) (:duration snapshot)))
      (is (= 3 (:scheduledBlinkCount snapshot)))
      (is (= 1 (:scheduledBurstCount snapshot))))
    ((:unsubscribe commands))
    (.dispose ^js agency)))

(deftest fast-blink-emits-cross-agency-signal
  (let [agency (polymer/createBlinkAgency #js {:frequency 45})
        status (status-events agency)]
    (.triggerBlink ^js agency)
    (is (some #(and (= "signal" (:type %))
                    (= "blink-fast" (:signal %)))
              @(:events status)))
    ((:unsubscribe status))
    (.dispose ^js agency)))

(deftest character-system-forwards-agency-streams
  (let [system (polymer/createCharacterAgencies nil)
        status (status-events system)
        commands (command-events system)]
    (.dispatch ^js system #js {:agency "blink" :command #js {:type "triggerBlink"}})
    (is (some #(= "state" (:type %)) @(:events status)))
    (is (= "scheduleSnippet" (:type (first @(:events commands)))))
    ((:unsubscribe status))
    ((:unsubscribe commands))
    (.dispose ^js system)))

(deftest disposed-agency-stops-events
  (let [agency (polymer/createBlinkAgency nil)
        commands (command-events agency)]
    (.dispose ^js agency)
    (.triggerBlink ^js agency)
    (is (empty? @(:events commands)))))

(deftest automatic-blink-uses-enabled-timer
  (async done
    (let [agency (polymer/createBlinkAgency #js {:frequency 60 :randomness 0})
          commands (command-events agency)]
      (.enable ^js agency)
      (js/setTimeout
       (fn []
         (try
           (is (seq @(:events commands)))
           ((:unsubscribe commands))
           (.dispose ^js agency)
           (done)
           (catch :default error
             (.dispose ^js agency)
             (throw error))))
       1150))))
