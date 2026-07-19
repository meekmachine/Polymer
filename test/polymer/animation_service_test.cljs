(ns polymer.animation-service-test
  (:require [cljs.test :refer [deftest is]]
            [polymer.core :as polymer]))

(defn collect-animation-events []
  (let [events (atom [])
        events-port (aget polymer/animationEventEmitter "events")
        subscribe (aget events-port "subscribe")
        subscription (.call subscribe events-port #(swap! events conj (js->clj % :keywordize-keys true)))]
    {:events events
     :unsubscribe #(.unsubscribe subscription)}))

(defn make-snippet-engine [calls]
  #js {:playSnippet
       (fn [snippet options]
         (swap! calls conj {:method "playSnippet"
                            :name (aget snippet "name")
                            :curves (js->clj (aget snippet "curves") :keywordize-keys true)
                            :options (js->clj options :keywordize-keys true)})
         #js {:clipName (aget snippet "name")
              :stop (fn [] (swap! calls conj {:method "stop" :name (aget snippet "name")}))})
       :setSnippetTime
       (fn [name time-sec]
         (swap! calls conj {:method "setSnippetTime"
                            :name name
                            :timeSec time-sec})
         true)
       :updateClipParams
       (fn [name params]
         (swap! calls conj {:method "updateClipParams"
                            :name name
                            :params (js->clj params :keywordize-keys true)})
         true)
       :cleanupSnippet
       (fn [name]
         (swap! calls conj {:method "cleanupSnippet" :name name})
         true)})

(defn make-baked-engine [calls]
  #js {:getAnimationClips
       (fn []
         #js [#js {:name "Idle"
                   :duration 2
                   :channels #js [#js {:channel "body"
                                        :trackCount 3
                                        :playable true}]}])
       :playAnimation
       (fn [name options]
         (swap! calls conj {:method "playAnimation"
                            :name name
                            :options (js->clj options :keywordize-keys true)})
         #js {:name name})
       :setAnimationSpeed
       (fn [name speed]
         (swap! calls conj {:method "setAnimationSpeed" :name name :speed speed}))
       :seekAnimation
       (fn [name time-sec]
         (swap! calls conj {:method "seekAnimation" :name name :timeSec time-sec}))
       :stopAnimation
       (fn [name]
         (swap! calls conj {:method "stopAnimation" :name name}))})

(deftest animation-service-schedules-snippets-through-polymer-animation
  (let [calls (atom [])
        events (collect-animation-events)
        service (polymer/createAnimationService (make-snippet-engine calls))]
    (.schedule ^js service
               #js {:name "manual:blink"
                    :curves #js {"43" #js [#js {:time 0 :intensity 0}
                                            #js {:time 0.04 :intensity 1}
                                            #js {:time 0.08 :intensity 0}]}
                    :loop false}
               #js {:priority 3})
    (is (= "playSnippet" (:method (first @calls))))
    (is (= "manual:blink" (:name (first @calls))))
    (is (= 3 (get-in (first @calls) [:options :priority])))
    (is (some #(and (= "SNIPPET_ADDED" (:type %))
                    (= "manual:blink" (:snippetName %)))
              @(:events events)))
    (is (some #(and (= "SNIPPET_PLAY_STATE_CHANGED" (:type %))
                    (= "manual:blink" (:snippetName %))
                    (:isPlaying %))
              @(:events events)))

    (.setSnippetTime ^js service "manual:blink" 0.03)
    (is (some #(and (= "setSnippetTime" (:method %))
                    (= "manual:blink" (:name %))
                    (= 0.03 (:timeSec %)))
              @calls))
    ((:unsubscribe events))
    (.dispose ^js service)))

(deftest animation-service-delegates-baked-clips-to-embody-engine
  (let [calls (atom [])
        service (polymer/createAnimationService (make-baked-engine calls))]
    (is (= ["Idle"]
           (mapv :name (js->clj (.getBakedClips ^js service) :keywordize-keys true))))
    (.playBakedAnimation ^js service "Idle" #js {:loopMode "repeat" :playbackRate 1.25})
    (is (some #(and (= "playAnimation" (:method %))
                    (= "Idle" (:name %))
                    (= 1.25 (get-in % [:options :playbackRate])))
              @calls))
    (is (= ["Idle"]
           (mapv :name (js->clj (.getPlayingBakedAnimations ^js service) :keywordize-keys true))))
    (.setBakedAnimationSpeed ^js service "Idle" 0.5)
    (.seekBakedAnimation ^js service "Idle" 1.2)
    (is (some #(and (= "setAnimationSpeed" (:method %))
                    (= 0.5 (:speed %)))
              @calls))
    (is (some #(and (= "seekAnimation" (:method %))
                    (= 1.2 (:timeSec %)))
              @calls))
    (.stopBakedAnimation ^js service "Idle")
    (is (empty? (js->clj (.getPlayingBakedAnimations ^js service) :keywordize-keys true)))
    (.dispose ^js service)))
