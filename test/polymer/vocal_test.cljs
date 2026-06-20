(ns polymer.vocal-test
  (:require [cljs.test :refer [deftest is testing]]
            [polymer.core :as polymer]
            [polymer.vocal.azure :as azure]
            [polymer.vocal.visemes :as visemes]))

(defn collect [target subscribe-fn]
  (let [events (atom [])]
    {:events events
     :unsubscribe (subscribe-fn target #(swap! events conj (js->clj % :keywordize-keys true)))}))

(defn domain-events [agency]
  (collect agency (fn [target listener] (.subscribeEvents ^js target listener))))

(defn effect-events [agency]
  (collect agency (fn [target listener] (.subscribeEffects ^js target listener))))

(defn make-runtime [calls]
  #js {:playSnippet
       (fn [name curves options]
         (swap! calls conj {:method "playSnippet"
                            :name name
                            :curves (js->clj curves :keywordize-keys true)
                            :options (js->clj options :keywordize-keys true)})
         #js {:clipName name
              :stop (fn [] (swap! calls conj {:method "stop" :name name}))
              :finished (js/Promise.resolve nil)})
       :setSnippetTime
       (fn [name offset-sec]
         (swap! calls conj {:method "setSnippetTime"
                            :name name
                            :offsetSec offset-sec})
         true)
       :cleanupSnippet
       (fn [name]
         (swap! calls conj {:method "cleanupSnippet" :name name})
         true)})

(deftest vocal-start-text-emits-one-animation-request
  (let [agency (polymer/createVocalAgency nil)
        events (domain-events agency)
        effects (effect-events agency)]
    (.dispatch ^js agency #js {:type "startText" :text "hello world"})
    (let [request (some #(when (= "animation.requestScheduleSnippet" (:type %)) %) @(:events events))
          snippet (:snippet request)
          snapshot (js->clj (.snapshot ^js agency) :keywordize-keys true)]
      (is request)
      (is (= "vocal" (:agency request)))
      (is (= "combined" (:snippetCategory snippet)))
      (is (= 1 (:snippetPlaybackRate snippet)))
      (is (false? (:autoVisemeJaw snippet)))
      (is (seq (get-in snippet [:curves :26])))
      (is (= ["hello" "world"] (map :word (:wordTimings snapshot))))
      (is (:speaking snapshot))
      (is (= 1 (:scheduledCount snapshot)))
      (is (empty? @(:events effects))))
    ((:unsubscribe events))
    ((:unsubscribe effects))
    (.dispose ^js agency)))

(deftest azure-visemes-map-to-canonical-timeline
  (let [timeline (azure/azure-visemes->timeline
                  [{:id 21 :time 0}
                   {:id 8 :time 0.12}
                   {:id 17 :time 0.32}]
                  700
                  {:wordTimings [{:word "mouth"
                                  :start 0
                                  :end 0.4}]})
        viseme-ids (map :visemeId timeline)]
    (is (some #(= (:B_M_P visemes/canonical-visemes) %) viseme-ids))
    (is (some #(= (:Oh visemes/canonical-visemes) %) viseme-ids))
    (is (some #(= (:W_OO visemes/canonical-visemes) %) viseme-ids))
    (is (some #(= (:Th visemes/canonical-visemes) %) viseme-ids))))

(deftest character-network-routes-vocal-to-animation-runtime
  (let [calls (atom [])
        system (polymer/createCharacterAgencies #js {:animation #js {:runtime (make-runtime calls)}})
        events (domain-events system)]
    (.dispatch ^js system
               #js {:agency "vocal"
                    :command #js {:type "startTimeline"
                                  :timeline #js {:name "voice:test"
                                                 :source "test"
                                                 :visemes #js [#js {:visemeId 1
                                                                    :offsetMs 0
                                                                    :durationMs 160}]}}})
    (is (some #(= "vocalTimelineStarted" (:type %)) @(:events events)))
    (is (some #(= "animationSnippetScheduled" (:type %)) @(:events events)))
    (is (= "playSnippet" (:method (first @calls))))
    (is (= "vocal" (get-in (first @calls) [:options :sourceAgency])))
    (is (seq (get-in (first @calls) [:curves :26])))
    ((:unsubscribe events))
    (.dispose ^js system)))

(deftest vocal-word-boundary-drift-seeks-through-animation
  (let [calls (atom [])
        system (polymer/createCharacterAgencies #js {:animation #js {:runtime (make-runtime calls)}})
        events (domain-events system)]
    (.dispatch ^js system
               #js {:agency "vocal"
                    :command #js {:type "startTimeline"
                                  :timeline #js {:name "voice:drift"
                                                 :source "azure"
                                                 :durationSec 1.2
                                                 :visemes #js [#js {:visemeId 1
                                                                    :offsetMs 0
                                                                    :durationMs 400}]
                                                 :wordTimings #js [#js {:word "hello"
                                                                        :startSec 0.1
                                                                        :endSec 0.5}]}}})
    (.dispatch ^js system
               #js {:agency "vocal"
                    :command #js {:type "wordBoundary"
                                  :word "hello"
                                  :wordIndex 0
                                  :observedElapsedSec 0.3}})
    (is (some #(= "vocalSyncDrift" (:type %)) @(:events events)))
    (is (some #(= "animationSnippetSeeked" (:type %)) @(:events events)))
    (is (some #(and (= "setSnippetTime" (:method %))
                    (= "voice:drift" (:name %))
                    (= 0.3 (:offsetSec %)))
              @calls))
    ((:unsubscribe events))
    (.dispose ^js system)))

(deftest vocal-can-receive-provider-word-timings-after-start
  (let [calls (atom [])
        system (polymer/createCharacterAgencies #js {:animation #js {:runtime (make-runtime calls)}})
        events (domain-events system)]
    (.dispatch ^js system
               #js {:agency "vocal"
                    :command #js {:type "startTimeline"
                                  :timeline #js {:name "voice:late-words"
                                                 :source "azure"
                                                 :durationSec 1.2
                                                 :visemes #js [#js {:visemeId 1
                                                                    :offsetMs 0
                                                                    :durationMs 400}]}}})
    (.dispatch ^js system
               #js {:agency "vocal"
                    :command #js {:type "updateWordTimings"
                                  :wordTimings #js [#js {:word "hello"
                                                         :startSec 0.1
                                                         :endSec 0.5}]}})
    (.dispatch ^js system
               #js {:agency "vocal"
                    :command #js {:type "wordBoundary"
                                  :word "hello"
                                  :wordIndex 0
                                  :observedElapsedSec 0.3}})
    (is (some #(= "vocalWordTimingsUpdated" (:type %)) @(:events events)))
    (is (some #(and (= "setSnippetTime" (:method %))
                    (= "voice:late-words" (:name %))
                    (= 0.3 (:offsetSec %)))
              @calls))
    ((:unsubscribe events))
    (.dispose ^js system)))

(deftest vocal-stop-requests-animation-removal-through-character-network
  (let [calls (atom [])
        system (polymer/createCharacterAgencies #js {:animation #js {:runtime (make-runtime calls)}})
        events (domain-events system)]
    (.dispatch ^js system
               #js {:agency "vocal"
                    :command #js {:type "startTimeline"
                                  :timeline #js {:name "voice:stop"
                                                 :source "test"
                                                 :visemes #js [#js {:visemeId 1
                                                                    :offsetMs 0
                                                                    :durationMs 160}]}}})
    (.dispatch ^js system #js {:agency "vocal" :command #js {:type "stop"}})
    (is (some #(and (= "animationSnippetRemoved" (:type %))
                    (= "voice:stop" (:name %)))
              @(:events events)))
    (is (some #(= "cleanupSnippet" (:method %)) @calls))
    ((:unsubscribe events))
    (.dispose ^js system)))
