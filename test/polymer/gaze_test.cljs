(ns polymer.gaze-test
  (:require [cljs.test :refer [async deftest is testing]]
            [polymer.core :as polymer]))

(defn collect-events
  [agency]
  (let [events (atom [])]
    {:events events
     :unsubscribe (.subscribeEvents ^js agency
                                    #(swap! events conj (js->clj % :keywordize-keys true)))}))

(defn request-events
  [events]
  (filter #(= "eyeHeadTracking.requestGaze" (:type %)) events))

(deftest gaze-normalizes-config
  (let [agency (polymer/createGazeAgency #js {:smoothFactor 99
                                              :minDelta -1
                                              :transitionDurationMs 10
                                              :coalesceMs 2000})
        snapshot (js->clj (.snapshot ^js agency) :keywordize-keys true)]
    (is (= 1 (get-in snapshot [:config :smoothFactor])))
    (is (= 0 (get-in snapshot [:config :minDelta])))
    (is (= 50 (get-in snapshot [:config :transitionDurationMs])))
    (is (= 1000 (get-in snapshot [:config :coalesceMs])))
    (.dispose ^js agency)))

(deftest gaze-plans-smoothed-mirrored-target-and-requests-eye-head-work
  (let [agency (polymer/createGazeAgency #js {:smoothFactor 1
                                              :mirrored true
                                              :coalesceMs 0
                                              :eyeIntensity 0.8
                                              :headIntensity 0.4})
        events (collect-events agency)]
    (.setTarget ^js agency #js {:x 0.5 :y -0.25})
    (let [planned (some #(when (= "gaze.targetPlanned" (:type %)) %) @(:events events))
          request (first (request-events @(:events events)))
          snapshot (js->clj (.snapshot ^js agency) :keywordize-keys true)]
      (is (= {:x -0.5 :y -0.25 :z 0} (:target planned)))
      (is (= "eyeHeadTracking" (:targetAgency request)))
      (is (= {:x -0.5 :y -0.25 :z 0} (:target request)))
      (is (= 0.8 (:eyeIntensity request)))
      (is (= 0.4 (:headIntensity request)))
      (is (= 1 (:requestedCount snapshot)))
      (is (= (:target request) (:lastRequestedTarget snapshot))))
    ((:unsubscribe events))
    (.dispose ^js agency)))

(deftest gaze-ignores-small-targets-below-min-delta
  (let [agency (polymer/createGazeAgency #js {:smoothFactor 1
                                              :minDelta 0.5
                                              :coalesceMs 0})
        events (collect-events agency)]
    (.setTarget ^js agency #js {:x 0.1 :y 0.1})
    (let [ignored (some #(when (= "gaze.targetIgnored" (:type %)) %) @(:events events))
          snapshot (js->clj (.snapshot ^js agency) :keywordize-keys true)]
      (is (= "min-delta" (:reason ignored)))
      (is (empty? (request-events @(:events events))))
      (is (= 1 (:ignoredCount snapshot))))
    ((:unsubscribe events))
    (.dispose ^js agency)))

(deftest gaze-selects-strongest-attention-candidate-through-domain-transform
  (let [agency (polymer/createGazeAgency #js {:smoothFactor 1
                                              :coalesceMs 0})
        events (collect-events agency)]
    (.dispatch ^js agency
               #js {:type "attention.fact"
                    :targets #js [#js {:target #js {:x -0.2 :y 0.1}
                                       :priority 0.2
                                       :label "weak"}
                                  #js {:target #js {:x 0.7 :y -0.3}
                                       :priority 0.6
                                       :confidence 0.5
                                       :label "strong"}]})
    (let [request (first (request-events @(:events events)))]
      (is (= "strong" (:label request)))
      (is (= {:x 0.7 :y -0.3 :z 0} (:target request))))
    ((:unsubscribe events))
    (.dispose ^js agency)))

(deftest gaze-coalesces-overlapping-target-requests
  (async done
         (let [agency (polymer/createGazeAgency #js {:smoothFactor 1
                                                     :coalesceMs 40})
               events (collect-events agency)]
           (.setTarget ^js agency #js {:x 0.2 :y 0.1})
           (.setTarget ^js agency #js {:x 0.6 :y 0.2})
           (is (empty? (request-events @(:events events))))
           (js/setTimeout
            (fn []
              (try
                (let [requests (vec (request-events @(:events events)))
                      snapshot (js->clj (.snapshot ^js agency) :keywordize-keys true)]
                  (is (= 1 (count requests)))
                  (is (= {:x 0.6 :y 0.2 :z 0} (:target (first requests))))
                  (is (= 1 (:requestedCount snapshot)))
                  ((:unsubscribe events))
                  (.dispose ^js agency)
                  (done))
                (catch :default error
                  (.dispose ^js agency)
                  (throw error))))
            80))))

(deftest gaze-reset-cancels-pending-request
  (async done
         (let [agency (polymer/createGazeAgency #js {:smoothFactor 1
                                                     :coalesceMs 50})
               events (collect-events agency)]
           (.setTarget ^js agency #js {:x 0.8 :y 0.2})
           (.reset ^js agency 120)
           (js/setTimeout
            (fn []
              (try
                (let [reset-request (some #(when (= "eyeHeadTracking.requestReset" (:type %)) %)
                                          @(:events events))
                      snapshot (js->clj (.snapshot ^js agency) :keywordize-keys true)]
                  (is reset-request)
                  (is (= 120 (:durationMs reset-request)))
                  (is (empty? (request-events @(:events events))))
                  (is (= 1 (:resetCount snapshot)))
                  ((:unsubscribe events))
                  (.dispose ^js agency)
                  (done))
                (catch :default error
                  (.dispose ^js agency)
                  (throw error))))
            90))))

(deftest character-network-exposes-gaze-agency-and-events
  (let [system (polymer/createCharacterAgencies #js {:gaze #js {:smoothFactor 1
                                                               :coalesceMs 0}})
        events (collect-events system)]
    (.dispatch ^js system #js {:agency "gaze"
                               :command #js {:type "setTarget"
                                             :target #js {:x 0.3 :y 0.2}}})
    (let [snapshot (js->clj (.snapshot ^js system) :keywordize-keys true)]
      (is (= "gaze" (get-in snapshot [:gaze :agency])))
      (is (some #(= "eyeHeadTracking.requestGaze" (:type %)) @(:events events)))
      (is (.agency ^js system "gaze")))
    ((:unsubscribe events))
    (.dispose ^js system)))
