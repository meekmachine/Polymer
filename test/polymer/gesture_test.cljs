(ns polymer.gesture-test
  (:require [cljs.test :refer [deftest is testing]]
            [polymer.core :as polymer]))

(def wave-emoji "👋")

(def wave-gesture
  {:id "wave"
   :name "Wave"
   :description "A small left-hand greeting gesture."
   :emoji wave-emoji
   :scope "left"
   :createdAt 100
   :updatedAt 200
   :captureSource "manual"
   :sourceText "wave with the left hand"
   :textRepresentation "HAND_L rz=45deg tx=0.1"
   :durationMs 800
   :priority 60
   :returnToBase true
   :affectedBones ["HAND_L"]
   :affectedAUs ["99"]
   :bones {"HAND_L" {:rotation [0 0 0.3826834323650898 0.9238795325112867]
                     :position [0.1 0 0]}}})

(def point-gesture
  {:id "point"
   :name "Point"
   :durationMs 500
   :keyframes [{:timeMs 0
                :bones {"ARM_R" {:rotation [0 0 0 1]}}}
               {:timeMs 250
                :bones {"ARM_R" {:rotation [0.25881904510252074 0 0 0.9659258262890683]}}}]})

(defn collect [target subscribe-fn]
  (let [events (atom [])]
    {:events events
     :unsubscribe (subscribe-fn target #(swap! events conj (js->clj % :keywordize-keys true)))}))

(defn domain-events [agency]
  (collect agency (fn [target listener] (.subscribeEvents ^js target listener))))

(defn effect-events [agency]
  (collect agency (fn [target listener] (.subscribeEffects ^js target listener))))

(defn scheduled-requests [events]
  (->> @(:events events)
       (keep (fn [event]
               (when (= "animation.requestScheduleSnippet" (:type event))
                 event)))))

(defn remove-requests [events]
  (->> @(:events events)
       (keep (fn [event]
               (when (= "animation.requestRemoveSnippet" (:type event))
                 event)))))

(defn error-events [events]
  (filter #(= "error" (:type %)) @(:events events)))

(defn make-runtime [calls]
  #js {:playTypedSnippet
       (fn [snippet options]
         (swap! calls conj {:method "playTypedSnippet"
                            :name (aget snippet "name")
                            :channels (js->clj (aget snippet "channels") :keywordize-keys true)
                            :options (js->clj options :keywordize-keys true)})
         #js {:clipName (aget snippet "name")
              :stop (fn [] (swap! calls conj {:method "stop" :name (aget snippet "name")}))
              :finished (js/Promise.resolve nil)})
       :cleanupSnippet
       (fn [name]
         (swap! calls conj {:method "cleanupSnippet" :name name})
         true)})

(deftest gesture-play-emoji-emits-typed-bone-snippet
  (let [agency (polymer/createGestureAgency
                (clj->js {:gestures {"wave" wave-gesture}
                          :emojiMappings {wave-emoji "wave"}}))
        events (domain-events agency)
        effects (effect-events agency)]
    (.dispatch ^js agency #js {:type "playEmoji" :emoji wave-emoji})
    (let [request (first (scheduled-requests events))
          snippet (:snippet request)
          channels (:channels snippet)
          rz-channel (some #(when (and (= "HAND_L" (get-in % [:target :id]))
                                       (= "rz" (get-in % [:target :channel])))
                              %)
                           channels)
          tx-channel (some #(when (and (= "HAND_L" (get-in % [:target :id]))
                                       (= "tx" (get-in % [:target :channel])))
                              %)
                           channels)
          snapshot (js->clj (.snapshot ^js agency) :keywordize-keys true)]
      (is request)
      (is (= "gesture" (:agency request)))
      (is (= "gesture" (get-in snippet [:metadata :agency])))
      (is (= "wave" (get-in snippet [:metadata :gestureId])))
      (is (= wave-emoji (get-in snippet [:metadata :emoji])))
      (is (= "left" (get-in snippet [:metadata :scope])))
      (is (= "manual" (get-in snippet [:metadata :captureSource])))
      (is (= "wave with the left hand" (get-in snippet [:metadata :sourceText])))
      (is (= "HAND_L rz=45deg tx=0.1" (get-in snippet [:metadata :textRepresentation])))
      (is (= 0.8 (:maxTime snippet)))
      (is (seq channels))
      (is rz-channel)
      (is tx-channel)
      (is (= "bone" (get-in rz-channel [:target :type])))
      (is (< 44 (get-in rz-channel [:target :maxDegrees]) 46))
      (is (= 0 (:intensity (first (:keyframes rz-channel)))))
      (is (= 0 (:intensity (last (:keyframes rz-channel)))))
      (is (= 1 (:scheduledCount snapshot)))
      (is (= 1 (count (.schedulerQueue ^js agency))))
      (is (empty? @(:events effects))))
    ((:unsubscribe events))
    ((:unsubscribe effects))
    (.dispose ^js agency)))

(deftest gesture-uses-config-defaults-for-missing-timing
  (let [gesture (dissoc wave-gesture :durationMs :priority)
        agency (polymer/createGestureAgency
                (clj->js {:defaultDurationMs 1200
                          :priority 72
                          :gestures {"wave" gesture}}))
        events (domain-events agency)]
    (.dispatch ^js agency #js {:type "playGesture" :gestureId "wave"})
    (let [snippet (:snippet (first (scheduled-requests events)))]
      (is (= 1.2 (:maxTime snippet)))
      (is (= 1200 (get-in snippet [:metadata :durationMs])))
      (is (= 72 (:snippetPriority snippet))))
    ((:unsubscribe events))
    (.dispose ^js agency)))

(deftest gesture-stop-emits-remove-request
  (let [agency (polymer/createGestureAgency
                (clj->js {:gestures {"wave" wave-gesture}
                          :emojiMappings {wave-emoji "wave"}}))
        events (domain-events agency)]
    (.dispatch ^js agency #js {:type "playGesture" :gestureId "wave"})
    (let [scheduled-name (get-in (first (scheduled-requests events)) [:snippet :name])]
      (.dispatch ^js agency #js {:type "stopGesture" :gestureId "wave"})
      (let [remove-request (first (remove-requests events))
            remove-event (some #(when (= "gestureRemoved" (:type %)) %) @(:events events))
            snapshot (js->clj (.snapshot ^js agency) :keywordize-keys true)]
        (is (= scheduled-name (:name remove-request)))
        (is (= scheduled-name (:name remove-event)))
        (is (= "requested" (:reason remove-request)))
        (is (empty? (:activeSnippets snapshot)))
        (is (= 1 (:removedCount snapshot)))))
    ((:unsubscribe events))
    (.dispose ^js agency)))

(deftest gesture-missing-id-emits-error-without-animation-request
  (let [agency (polymer/createGestureAgency nil)
        events (domain-events agency)]
    (.dispatch ^js agency #js {:type "playGesture" :gestureId "missing"})
    (is (seq (error-events events)))
    (is (empty? (scheduled-requests events)))
    ((:unsubscribe events))
    (.dispose ^js agency)))

(deftest gesture-respects-max-active-when-not-replacing
  (let [agency (polymer/createGestureAgency
                (clj->js {:replaceActive false
                          :maxActive 1
                          :gestures {"wave" wave-gesture
                                     "point" point-gesture}}))
        events (domain-events agency)]
    (.dispatch ^js agency #js {:type "playGesture" :gestureId "wave"})
    (.dispatch ^js agency #js {:type "playGesture" :gestureId "point"})
    (let [snapshot (js->clj (.snapshot ^js agency) :keywordize-keys true)]
      (is (= 1 (count (scheduled-requests events))))
      (is (= 1 (:scheduledCount snapshot)))
      (is (= 1 (count (:activeSnippets snapshot)))))
    ((:unsubscribe events))
    (.dispose ^js agency)))

(deftest gesture-configure-can-refresh-library
  (let [agency (polymer/createGestureAgency nil)
        events (domain-events agency)]
    (.configure ^js agency (clj->js {:gestures {"point" point-gesture}}))
    (.dispatch ^js agency #js {:type "playGesture" :gestureId "point"})
    (let [config-event (some #(when (= "gestureConfigChanged" (:type %)) %) @(:events events))
          snippet (:snippet (first (scheduled-requests events)))]
      (is (= ["point"] (get-in config-event [:state :gestures])))
      (is snippet)
      (is (= ["ARM_R"] (get-in snippet [:metadata :affectedBones]))))
    ((:unsubscribe events))
    (.dispose ^js agency)))

(deftest gesture-keyframes-build-dynamic-bone-channel
  (let [agency (polymer/createGestureAgency
                (clj->js {:gestures {"point" point-gesture}}))
        events (domain-events agency)]
    (.dispatch ^js agency #js {:type "playGesture" :gestureId "point"})
    (let [snippet (:snippet (first (scheduled-requests events)))
          rx-channel (some #(when (= "rx" (get-in % [:target :channel])) %)
                           (:channels snippet))]
      (is rx-channel)
      (is (= "ARM_R" (get-in rx-channel [:target :id])))
      (is (= 0.5 (:maxTime snippet)))
      (is (= 0 (:time (first (:keyframes rx-channel)))))
      (is (= 0.25 (:time (second (:keyframes rx-channel)))))
      (is (= 0 (:intensity (last (:keyframes rx-channel))))))
    ((:unsubscribe events))
    (.dispose ^js agency)))

(deftest individual-gesture-agency-can-route-to-animation-agency
  (let [calls (atom [])
        animation (polymer/createAnimationAgency #js {:runtime (make-runtime calls)})
        gesture (polymer/createGestureAgency
                 (clj->js {:gestures {"wave" wave-gesture}
                           :emojiMappings {wave-emoji "wave"}}))
        unsubscribe (.subscribeEvents
                     ^js gesture
                     (fn [event]
                       (let [payload (js->clj event :keywordize-keys true)]
                         (case (:type payload)
                           "animation.requestScheduleSnippet"
                           (.dispatch ^js animation
                                      (clj->js {:type "scheduleSnippet"
                                                :sourceAgency "gesture"
                                                :snippet (:snippet payload)
                                                :options (:options payload)}))

                           "animation.requestRemoveSnippet"
                           (.dispatch ^js animation
                                      (clj->js {:type "removeSnippet"
                                                :sourceAgency "gesture"
                                                :name (:name payload)}))

                           nil))))]
    (.dispatch ^js gesture #js {:type "playEmoji" :emoji wave-emoji})
    (is (= "playTypedSnippet" (:method (first @calls))))
    (is (= "gesture" (get-in (first @calls) [:options :sourceAgency])))
    (is (some #(and (= "bone" (get-in % [:target :type]))
                    (= "HAND_L" (get-in % [:target :id])))
              (:channels (first @calls))))
    (unsubscribe)
    (.dispose ^js gesture)
    (.dispose ^js animation)))
