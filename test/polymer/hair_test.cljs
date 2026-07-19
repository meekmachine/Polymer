(ns polymer.hair-test
  (:require [cljs.test :refer [async deftest is testing]]
            [polymer.hair.agency :as hair]))

(defn collect-events
  [agency]
  (let [events (atom [])]
    {:events events
     :unsubscribe (.subscribeEvents ^js agency
                                    #(swap! events conj (js->clj % :keywordize-keys true)))}))

(deftest hair-normalizes-physics-config
  (let [agency (hair/create-hair-agency #js {:physics #js {:responseScale 99
                                                          :coalesceMs -10
                                                          :impulseClipDurationMs 99999}})
        snapshot (js->clj (.snapshot ^js agency) :keywordize-keys true)]
    (is (= 10 (get-in snapshot [:physics :responseScale])))
    (is (= 0 (get-in snapshot [:physics :coalesceMs])))
    (is (= 5000 (get-in snapshot [:physics :impulseClipDurationMs])))
    (.dispose ^js agency)))

(deftest register-objects-classifies-eyebrows-without-runtime-imports
  (let [agency (hair/create-hair-agency nil)
        events (collect-events agency)]
    (.dispatch ^js agency #js {:type "registerObjects"
                               :objects #js [#js {:name "SideHair" :isMesh true}
                                             #js {:name "Left_Eyebrow" :isMesh true}]})
    (let [snapshot (js->clj (.snapshot ^js agency) :keywordize-keys true)]
      (is (= 2 (count (:objects snapshot))))
      (is (true? (:isEyebrow (second (:objects snapshot)))))
      (is (some #(and (= "hair.requestRuntime" (:type %))
                      (= "applyState" (:action %)))
                @(:events events))))
    ((:unsubscribe events))
    (.dispose ^js agency)))

(deftest immediate-motion-emits-runtime-request
  (let [agency (hair/create-hair-agency #js {:physics #js {:coalesceMs 0
                                                          :responseScale 1}})
        events (collect-events agency)]
    (.dispatch ^js agency #js {:type "motionFact"
                               :velocity #js {:x 0.4 :y 0 :z 0}})
    (let [request (some #(when (= "hair.requestRuntime" (:type %)) %) @(:events events))
          snapshot (js->clj (.snapshot ^js agency) :keywordize-keys true)]
      (is request)
      (is (= "applyMotion" (:action request)))
      (is (= "followMotion" (:mode request)))
      (is (= 1 (:motionCount snapshot)))
      (is (= 1 (:runtimeRequestCount snapshot))))
    ((:unsubscribe events))
    (.dispose ^js agency)))

(deftest scheduler-coalesces-rapid-motion-facts
  (async done
         (let [agency (hair/create-hair-agency #js {:physics #js {:coalesceMs 10
                                                                 :responseScale 1}})
               events (collect-events agency)]
           (.dispatch ^js agency #js {:type "motionFact"
                                      :velocity #js {:x 0.1 :y 0 :z 0}})
           (.dispatch ^js agency #js {:type "motionFact"
                                      :velocity #js {:x 0.8 :y 0 :z 0}})
           (js/setTimeout
            (fn []
              (try
                (let [requests (filter #(and (= "hair.requestRuntime" (:type %))
                                             (= "applyMotion" (:action %)))
                                       @(:events events))
                      request (first requests)]
                  (is (= 1 (count requests)))
                  (is (= 0.8 (get-in request [:velocity :x]))))
                ((:unsubscribe events))
                (.dispose ^js agency)
                (done)
                (catch :default error
                  (.dispose ^js agency)
                  (throw error))))
            35))))

(deftest reset-emits-runtime-reset
  (let [agency (hair/create-hair-agency nil)
        events (collect-events agency)]
    (.reset ^js agency)
    (let [snapshot (js->clj (.snapshot ^js agency) :keywordize-keys true)]
      (is (= 1 (:resetCount snapshot)))
      (is (some #(and (= "hair.requestRuntime" (:type %))
                      (= "reset" (:action %)))
                @(:events events))))
    ((:unsubscribe events))
    (.dispose ^js agency)))

(deftest disposed-hair-stops-emitting
  (let [agency (hair/create-hair-agency #js {:physics #js {:coalesceMs 0}})
        events (collect-events agency)]
    (.dispose ^js agency)
    (.dispatch ^js agency #js {:type "motionFact" :velocity #js {:x 1}})
    (is (empty? @(:events events)))))
