(ns polymer.eye-head-test
  (:require [cljs.test :refer [async deftest is]]
            [polymer.core :as polymer]))

(defn collect-events
  [agency]
  (let [events (atom [])]
    {:events events
     :unsubscribe (.subscribeEvents ^js agency
                                    #(swap! events conj (js->clj % :keywordize-keys true)))}))

(defn animation-requests
  [events]
  (filter #(= "animation.requestScheduleSnippet" (:type %)) events))

(defn remove-requests
  [events]
  (filter #(= "animation.requestRemoveSnippet" (:type %)) events))

(defn channel-ids
  [snippet]
  (set (map #(get-in % [:target :id]) (:channels snippet))))

(defn make-runtime
  [calls]
  #js {:playTypedSnippet
       (fn [snippet options]
         (swap! calls conj {:method "playTypedSnippet"
                            :name (aget snippet "name")
                            :channels (js->clj (aget snippet "channels") :keywordize-keys true)
                            :options (js->clj options :keywordize-keys true)})
         #js {:clipName (aget snippet "name")
              :stop (fn [] true)
              :finished (js/Promise.resolve nil)})
       :cleanupSnippet
       (fn [name]
         (swap! calls conj {:method "cleanupSnippet"
                            :name name})
         true)})

(deftest eye-head-schedules-one-typed-snippet-for-synchronized-gaze
  (let [agency (polymer/createEyeHeadTrackingAgency #js {:coalesceMs 0})
        events (collect-events agency)]
    (.dispatch ^js agency
               #js {:type "eyeHeadTracking.requestGaze"
                    :requestId "gaze:test"
                    :target #js {:x 0.5 :y 0.25}
                    :eyeDurationMs 120
                    :headDurationMs 300
                    :eyeIntensity 1
                    :headIntensity 0.5})
    (let [request (first (animation-requests @(:events events)))
          snippet (:snippet request)
          ids (channel-ids snippet)]
      (is (= "eyeHeadTracking" (:agency request)))
      (is (= "eyeHeadTracking:gaze:gaze:test" (:name snippet)))
      (is (= 0.3 (:maxTime snippet)))
      (is (every? ids [61 62 63 64 51 52 53 54 55 56]))
      (is (= 10 (count (:channels snippet))))
      (is (= 1.0 (:snippetIntensityScale snippet)))
      (is (= "eyeHeadTracking" (get-in request [:options :sourceAgency]))))
    ((:unsubscribe events))
    (.dispose ^js agency)))

(deftest eye-head-ignores-requests-when-movement-is-disabled
  (let [agency (polymer/createEyeHeadTrackingAgency #js {:eyeTrackingEnabled false
                                                         :headTrackingEnabled false
                                                         :coalesceMs 0})
        events (collect-events agency)]
    (.dispatch ^js agency
               #js {:type "eyeHeadTracking.requestGaze"
                    :requestId "gaze:disabled"
                    :target #js {:x 0.5 :y 0.25}})
    (let [ignored (some #(when (= "eyeHeadTracking.requestIgnored" (:type %)) %)
                        @(:events events))]
      (is ignored)
      (is (= "movement-disabled" (:reason ignored)))
      (is (empty? (animation-requests @(:events events)))))
    ((:unsubscribe events))
    (.dispose ^js agency)))

(deftest eye-head-replaces-active-snippet-on-next-request
  (let [agency (polymer/createEyeHeadTrackingAgency #js {:coalesceMs 0})
        events (collect-events agency)]
    (.dispatch ^js agency
               #js {:type "eyeHeadTracking.requestGaze"
                    :requestId "gaze:first"
                    :target #js {:x 0.25 :y 0}})
    (.dispatch ^js agency
               #js {:type "eyeHeadTracking.requestGaze"
                    :requestId "gaze:second"
                    :target #js {:x -0.25 :y 0}})
    (let [removes (vec (remove-requests @(:events events)))
          requests (vec (animation-requests @(:events events)))
          snapshot (js->clj (.snapshot ^js agency) :keywordize-keys true)]
      (is (= 2 (count requests)))
      (is (= 1 (count removes)))
      (is (= "eyeHeadTracking:gaze:gaze:first" (:name (first removes))))
      (is (= ["eyeHeadTracking:gaze:gaze:second"] (:activeSnippetNames snapshot))))
    ((:unsubscribe events))
    (.dispose ^js agency)))

(deftest eye-head-reset-builds-centered-snippet-and-cancels-prior-work
  (let [agency (polymer/createEyeHeadTrackingAgency #js {:coalesceMs 0})
        events (collect-events agency)]
    (.dispatch ^js agency
               #js {:type "eyeHeadTracking.requestGaze"
                    :requestId "gaze:before-reset"
                    :target #js {:x 0.75 :y 0.4}})
    (.reset ^js agency 180)
    (let [requests (vec (animation-requests @(:events events)))
          reset-request (last requests)
          reset-snippet (:snippet reset-request)
          snapshot (js->clj (.snapshot ^js agency) :keywordize-keys true)]
      (is (= 2 (count requests)))
      (is (= {:x 0 :y 0 :z 0} (get-in reset-snippet [:metadata :target])))
      (is (= 0.18 (:maxTime reset-snippet)))
      (is (= 1 (:resetCount snapshot))))
    ((:unsubscribe events))
    (.dispose ^js agency)))

(deftest eye-head-coalesces-overlapping-gaze-requests
  (async done
         (let [agency (polymer/createEyeHeadTrackingAgency #js {:coalesceMs 40})
               events (collect-events agency)]
           (.dispatch ^js agency
                      #js {:type "eyeHeadTracking.requestGaze"
                           :requestId "gaze:first"
                           :target #js {:x 0.2 :y 0.1}})
           (.dispatch ^js agency
                      #js {:type "eyeHeadTracking.requestGaze"
                           :requestId "gaze:second"
                           :target #js {:x 0.6 :y 0.2}})
           (is (empty? (animation-requests @(:events events))))
           (js/setTimeout
            (fn []
              (try
                (let [requests (vec (animation-requests @(:events events)))]
                  (is (= 1 (count requests)))
                  (is (= "eyeHeadTracking:gaze:gaze:second"
                         (get-in (first requests) [:snippet :name])))
                  ((:unsubscribe events))
                  (.dispose ^js agency)
                  (done))
                (catch :default error
                  (.dispose ^js agency)
                  (throw error))))
            80))))

(deftest character-network-routes-gaze-through-eye-head-to-animation
  (let [calls (atom [])
        system (polymer/createCharacterAgencies
                #js {:gaze #js {:smoothFactor 1 :coalesceMs 0}
                     :eyeHeadTracking #js {:coalesceMs 0}
                     :animation #js {:runtime (make-runtime calls)}})
        events (collect-events system)]
    (.dispatch ^js system
               #js {:agency "gaze"
                    :command #js {:type "setTarget"
                                  :target #js {:x 0.4 :y 0.1}}})
    (let [snapshot (js->clj (.snapshot ^js system) :keywordize-keys true)
          played (first @calls)]
      (is (= "eyeHeadTracking" (get-in snapshot [:eyeHeadTracking :agency])))
      (is (some #(= "eyeHeadTracking.requestGaze" (:type %)) @(:events events)))
      (is (some #(= "animation.requestScheduleSnippet" (:type %)) @(:events events)))
      (is (= "playTypedSnippet" (:method played)))
      (is (= "eyeHeadTracking" (get-in played [:options :sourceAgency])))
      (is (contains? (set (map #(get-in % [:target :id]) (:channels played))) 62)))
    ((:unsubscribe events))
    (.dispose ^js system)))
