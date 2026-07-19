(ns polymer.camera-context-test
  (:require [cljs.test :refer [async deftest is testing]]
            [polymer.camera-context.agency :as camera-context]
            [polymer.camera-context.domain :as domain]))

(defn collect-events
  [agency]
  (let [events (atom [])]
    {:events events
     :unsubscribe (.subscribeEvents ^js agency
                                    #(swap! events conj (js->clj % :keywordize-keys true)))}))

(deftest camera-relative-offset-is-pure-data
  (let [offset (domain/compute-camera-relative-offset
                {:x 1 :y 1 :z 2}
                {:x 0 :y 0 :z 0}
                domain/identity-quaternion
                {:yawWeight 0.35 :pitchWeight 0.2})]
    (is (pos? (:x offset)))
    (is (pos? (:y offset)))
    (is (<= (:x offset) 0.35))
    (is (<= (:y offset) 0.2))))

(deftest camera-context-normalizes-config
  (let [agency (camera-context/create-camera-context-agency #js {:coalesceMs -10
                                                                :staleAfterMs 999999
                                                                :yawWeight 3})
        snapshot (js->clj (.snapshot ^js agency) :keywordize-keys true)]
    (is (= 0 (get-in snapshot [:config :coalesceMs])))
    (is (= 60000 (get-in snapshot [:config :staleAfterMs])))
    (is (= 1 (get-in snapshot [:config :yawWeight])))
    (.dispose ^js agency)))

(deftest immediate-camera-update-publishes-fact
  (let [agency (camera-context/create-camera-context-agency #js {:coalesceMs 0})
        events (collect-events agency)]
    (.dispatch ^js agency #js {:type "updateCamera"
                               :cameraPosition #js {:x 1 :y 0 :z 2}
                               :targetPosition #js {:x 0 :y 0 :z 0}
                               :modelQuaternion #js {:x 0 :y 0 :z 0 :w 1}})
    (let [fact (some #(when (= "camera.fact" (:type %)) %) @(:events events))
          snapshot (js->clj (.snapshot ^js agency) :keywordize-keys true)]
      (is fact)
      (is (= "camera.relative" (:kind fact)))
      (is (= 1 (:publishedCount snapshot)))
      (is (= 1 (:updateCount snapshot))))
    ((:unsubscribe events))
    (.dispose ^js agency)))

(deftest camera-context-coalesces-rapid-updates
  (async done
         (let [agency (camera-context/create-camera-context-agency #js {:coalesceMs 10})
               events (collect-events agency)]
           (.dispatch ^js agency #js {:type "updateCamera"
                                      :cameraPosition #js {:x 1 :y 0 :z 2}
                                      :targetPosition #js {:x 0 :y 0 :z 0}})
           (.dispatch ^js agency #js {:type "updateCamera"
                                      :cameraPosition #js {:x 2 :y 0 :z 2}
                                      :targetPosition #js {:x 0 :y 0 :z 0}})
           (js/setTimeout
            (fn []
              (try
                (let [facts (filter #(= "camera.fact" (:type %)) @(:events events))
                      fact (first facts)]
                  (is (= 1 (count facts)))
                  (is (= 2 (get-in fact [:cameraPosition :x])))
                  (is (= 2 (:sequence fact))))
                ((:unsubscribe events))
                (.dispose ^js agency)
                (done)
                (catch :default error
                  (.dispose ^js agency)
                  (throw error))))
            35))))

(deftest stale-invalidation-publishes-stale-fact
  (let [agency (camera-context/create-camera-context-agency #js {:coalesceMs 0})
        events (collect-events agency)]
    (.dispatch ^js agency #js {:type "updateCamera"
                               :cameraPosition #js {:x 1 :y 0 :z 2}
                               :targetPosition #js {:x 0 :y 0 :z 0}})
    (.invalidateStale ^js agency "test")
    (let [stale (some #(when (= "camera.stale" (:type %)) %) @(:events events))
          snapshot (js->clj (.snapshot ^js agency) :keywordize-keys true)]
      (is stale)
      (is (:stale snapshot))
      (is (= "test" (:reason stale))))
    ((:unsubscribe events))
    (.dispose ^js agency)))

(deftest reset-clears-camera-facts
  (let [agency (camera-context/create-camera-context-agency #js {:coalesceMs 0})
        events (collect-events agency)]
    (.dispatch ^js agency #js {:type "updateCamera"
                               :cameraPosition #js {:x 1 :y 0 :z 2}
                               :targetPosition #js {:x 0 :y 0 :z 0}})
    (.reset ^js agency)
    (let [snapshot (js->clj (.snapshot ^js agency) :keywordize-keys true)]
      (is (nil? (:lastFact snapshot)))
      (is (= "idle" (:status snapshot)))
      (is (some #(and (= "camera.status" (:type %))
                      (= "reset" (:reason %)))
                @(:events events))))
    ((:unsubscribe events))
    (.dispose ^js agency)))

(deftest disposed-camera-context-stops-emitting
  (let [agency (camera-context/create-camera-context-agency #js {:coalesceMs 0})
        events (collect-events agency)]
    (.dispose ^js agency)
    (.dispatch ^js agency #js {:type "updateCamera"
                               :cameraPosition #js {:x 1 :y 0 :z 2}})
    (is (empty? @(:events events)))))
