(ns polymer.gesture-test
  (:require [cljs.test :refer [async deftest is testing]]
            [polymer.core :as polymer]
            [polymer.gesture.goap :as gesture-goap]
            [polymer.gesture.state :as gesture-state]))

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

(def left-greeting-gesture
  (assoc wave-gesture
         :id "left-wave"
         :name "Left Wave"
         :tags ["greeting" "wave"]
         :scope "left"
         :affectedBones ["HAND_L"]))

(def right-greeting-gesture
  (assoc wave-gesture
         :id "right-wave"
         :name "Right Wave"
         :tags ["greeting" "wave"]
         :scope "right"
         :affectedBones ["HAND_R"]
         :bones {"HAND_R" {:rotation [0 0 0.3826834323650898 0.9238795325112867]
                           :position [0.1 0 0]}}))

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

(defn plan-events [events]
  (filter #(= "gesturePlanCreated" (:type %)) @(:events events)))

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

(deftest gesture-unmapped-emoji-does-not-schedule-arbitrary-gesture
  (let [agency (polymer/createGestureAgency
                (clj->js {:gestures {"wave" wave-gesture}}))
        events (domain-events agency)]
    (.dispatch ^js agency #js {:type "playEmoji" :emoji "🤷"})
    (let [plan-event (first (plan-events events))]
      (is (empty? (scheduled-requests events)))
      (is plan-event)
      (is (:noop (:plan plan-event)))
      (is (= "no-gesture-candidate" (get-in plan-event [:plan :steps 0 :reason]))))
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

(deftest gesture-completion-clears-active-snippet-capacity
  (async done
         (let [quick-wave (assoc wave-gesture :durationMs 80)
               quick-point (assoc point-gesture :durationMs 80)
               agency (polymer/createGestureAgency
                       (clj->js {:replaceActive false
                                 :maxActive 1
                                 :gestures {"wave" quick-wave
                                            "point" quick-point}}))
               events (domain-events agency)]
           (.dispatch ^js agency #js {:type "playGesture" :gestureId "wave"})
           (js/setTimeout
            (fn []
              (try
                (.dispatch ^js agency #js {:type "playGesture" :gestureId "point"})
                (let [snapshot (js->clj (.snapshot ^js agency) :keywordize-keys true)
                      completed-remove (some #(when (and (= "animation.requestRemoveSnippet" (:type %))
                                                         (= "completed" (:reason %)))
                                                %)
                                             @(:events events))]
                  (is completed-remove)
                  (is (= 2 (count (scheduled-requests events))))
                  (is (= 2 (:scheduledCount snapshot)))
                  (is (= 1 (:removedCount snapshot)))
                  (is (= 1 (count (:activeSnippets snapshot)))))
                ((:unsubscribe events))
                (.dispose ^js agency)
                (done)
                (catch :default error
                  ((:unsubscribe events))
                  (.dispose ^js agency)
                  (throw error))))
            180))))

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

(deftest gesture-goap-selects-candidate-from-goal-constraints
  (let [world (gesture-state/config->state
               {:gestures {"left-wave" left-greeting-gesture
                           "right-wave" right-greeting-gesture}})
        plan (gesture-goap/plan-command
              {:type "gesture.goal"
               :intent "greeting"
               :scope "right"
               :avoidBones ["HAND_L"]}
              world
              1000)
        select-step (some #(when (= "select-gesture" (:op %)) %) (:steps plan))]
    (is (:ok plan))
    (is (= ["resolve-gesture-goal"
            "select-gesture"
            "build-gesture-snippet"
            "schedule-animation"
            "record-schedule"]
           (map :op (:steps plan))))
    (is (= "right-wave" (:gestureId select-step)))
    (is (= "right" (get-in select-step [:matches :scope])))))

(deftest gesture-goap-plans-active-conflict-instead-of-forwarding-command
  (let [active-snippet {:name "active-left"
                        :maxTime 0.8
                        :loop false
                        :snippetPriority 60
                        :metadata {:trigger "test"}}
        world (-> (gesture-state/config->state
                   {:replaceActive false
                    :maxActive 2
                    :gestures {"left-wave" left-greeting-gesture
                               "right-wave" right-greeting-gesture}})
                  (gesture-state/record-schedule left-greeting-gesture active-snippet 900))
        plan (gesture-goap/plan-command
              {:type "gesture.goal"
               :intent "greeting"
               :scope "left"
               :affectedBones ["HAND_L"]}
              world
              1000)
        ignore-step (some #(when (= "ignore" (:op %)) %) (:steps plan))]
    (is (:ok plan))
    (is (:noop plan))
    (is (= "active-conflict" (:reason ignore-step)))
    (is (= ["active-left"] (:names ignore-step)))))

(deftest gesture-goal-dispatch-schedules-selected-gesture-with-plan-metadata
  (let [agency (polymer/createGestureAgency
                (clj->js {:gestures {"left-wave" left-greeting-gesture
                                     "right-wave" right-greeting-gesture}}))
        events (domain-events agency)]
    (.performGoal ^js agency (clj->js {:intent "greeting"
                                       :scope "right"
                                       :avoidBones ["HAND_L"]
                                       :intensity 0.7}))
    (let [plan-event (first (plan-events events))
          request (first (scheduled-requests events))
          snippet (:snippet request)]
      (is plan-event)
      (is (= "right-wave" (get-in plan-event [:plan :gestureId])))
      (is (= "right-wave" (get-in snippet [:metadata :gestureId])))
      (is (= "goal" (get-in snippet [:metadata :trigger])))
      (is (= "greeting" (get-in snippet [:metadata :intent])))
      (is (= "right" (get-in snippet [:metadata :goal :scope])))
      (is (= "right-wave" (get-in snippet [:metadata :selection :gestureId])))
      (is (= 0.7 (:snippetIntensityScale snippet))))
    ((:unsubscribe events))
    (.dispose ^js agency)))

(deftest gesture-goap-removes-conflicting-active-snippet-only
  (let [agency (polymer/createGestureAgency
                (clj->js {:replaceActive true
                          :maxActive 2
                          :gestures {"left-wave" left-greeting-gesture
                                     "right-wave" right-greeting-gesture}}))
        events (domain-events agency)]
    (.dispatch ^js agency #js {:type "gesture.goal"
                               :intent "greeting"
                               :scope "left"
                               :affectedBones #js ["HAND_L"]})
    (.dispatch ^js agency #js {:type "gesture.goal"
                               :intent "greeting"
                               :scope "right"
                               :affectedBones #js ["HAND_R"]})
    (.dispatch ^js agency #js {:type "gesture.goal"
                               :intent "greeting"
                               :scope "left"
                               :affectedBones #js ["HAND_L"]})
    (let [removes (remove-requests events)
          snapshot (js->clj (.snapshot ^js agency) :keywordize-keys true)]
      (is (= 1 (count removes)))
      (is (= "conflict" (:reason (first removes))))
      (is (= 3 (count (scheduled-requests events))))
      (is (= 2 (count (:activeSnippets snapshot)))))
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

(deftest character-network-routes-gesture-to-animation-agency
  (let [calls (atom [])
        system (polymer/createCharacterAgencies
                (clj->js {:animation {:runtime (make-runtime calls)}
                          :gesture {:gestures {"wave" wave-gesture}
                                    :emojiMappings {wave-emoji "wave"}}}))
        events (domain-events system)
        effects (effect-events system)]
    (is (.agency ^js system "gesture"))
    (.dispatch ^js system #js {:agency "gesture"
                               :command #js {:type "playEmoji"
                                             :emoji wave-emoji}})
    (let [snapshot (js->clj (.snapshot ^js system) :keywordize-keys true)]
      (is (some #(= "gestureScheduled" (:type %)) @(:events events)))
      (is (some #(and (= "animation.requestScheduleSnippet" (:type %))
                      (= "gesture" (:agency %)))
                @(:events events)))
      (is (some #(= "animationSnippetScheduled" (:type %)) @(:events events)))
      (is (= "playTypedSnippet" (:method (first @calls))))
      (is (= "gesture" (get-in (first @calls) [:options :sourceAgency])))
      (is (= 1 (get-in snapshot [:gesture :scheduledCount]))))
    ((:unsubscribe events))
    ((:unsubscribe effects))
    (.dispose ^js system)))
