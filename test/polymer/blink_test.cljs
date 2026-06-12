(ns polymer.blink-test
  (:require [cljs.test :refer [async deftest is]]
            [polymer.core :as polymer]))

(defn collect [target method]
  (let [events (atom [])
        unsubscribe (method target #(swap! events conj (js->clj % :keywordize-keys true)))]
    {:events events
     :unsubscribe unsubscribe}))

(deftest state-commands-clamp
  (let [agency (polymer/createBlinkAgency nil)
        status (collect agency (fn [target listener] (.subscribeStatus ^js target listener)))]
    (.dispatch ^js agency #js {:type "setFrequency" :value 90})
    (.dispatch ^js agency #js {:type "setDuration" :value 2})
    (.dispatch ^js agency #js {:type "setBurstCount" :value 22})
    (let [snapshot (js->clj (.snapshot ^js agency) :keywordize-keys true)]
      (is (= 60 (:frequency snapshot)))
      (is (= 1 (:duration snapshot)))
      (is (= 8 (:burstCount snapshot))))
    ((:unsubscribe status))
    (.dispose ^js agency)))

(deftest manual-blink-emits-command
  (async done
    (let [agency (polymer/createBlinkAgency nil)
          commands (collect agency (fn [target listener] (.subscribeCommands ^js target listener)))]
      (.triggerBlink ^js agency)
      (js/setTimeout
       (fn []
         (let [events @(:events commands)
               schedule-events (filter #(= "scheduleSnippet" (:type %)) events)]
           (is (= 1 (count schedule-events)))
           (is (= "blink" (get-in (first schedule-events) [:snippet :snippetCategory])))
           ((:unsubscribe commands))
           (.dispose ^js agency)
           (done)))
       20))))

(deftest burst-blink-is-single-snippet-with-multiple-peaks
  (async done
    (let [agency (polymer/createBlinkAgency #js {:burstCount 3 :burstGap 0.08})
          commands (collect agency (fn [target listener] (.subscribeCommands ^js target listener)))]
      (.triggerBlink ^js agency #js {:burstCount 3})
      (js/setTimeout
       (fn []
         (let [schedule-event (first (filter #(= "scheduleSnippet" (:type %)) @(:events commands)))
               snippet (:snippet schedule-event)]
           (is (= 3 (get-in snippet [:metadata :blinkCount])))
           (is (> (:maxTime snippet) (:duration (js->clj (.snapshot ^js agency) :keywordize-keys true))))
           ((:unsubscribe commands))
           (.dispose ^js agency)
           (done)))
       20))))

(deftest disposed-agency-stops-events
  (let [agency (polymer/createBlinkAgency nil)
        commands (collect agency (fn [target listener] (.subscribeCommands ^js target listener)))]
    (.dispose ^js agency)
    (.triggerBlink ^js agency)
    (is (empty? @(:events commands)))))
