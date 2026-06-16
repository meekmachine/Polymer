(ns polymer.animation-test
  (:require [cljs.test :refer [async deftest is]]
            [polymer.core :as polymer]))

(defn collect [target subscribe-fn]
  (let [events (atom [])]
    {:events events
     :unsubscribe (subscribe-fn target #(swap! events conj (js->clj % :keywordize-keys true)))}))

(defn effect-events [agency]
  (collect agency (fn [target listener] (.subscribeEffects ^js target listener))))

(deftest animation-agency-emits-host-effects-and-cleans-up
  (async done
    (let [agency (polymer/createAnimationAgency nil)
          effects (effect-events agency)]
      (.dispatch ^js agency #js {:type "scheduleSnippet"
                                 :sourceAgency "test"
                                 :snippet #js {:name "test:blink"
                                               :curves #js {}
                                               :maxTime 0.05
                                               :loop false
                                               :snippetCategory "test"
                                               :snippetPriority 1}
                                 :options #js {:autoPlay true}})
      (is (= "animation.scheduleSnippet" (:type (first @(:events effects)))))
      (is (= "animation" (:agency (first @(:events effects)))))
      (is (= "test" (:sourceAgency (first @(:events effects)))))
      (is (some (fn [entry] (= "test:blink" (:name entry)))
                (vals (:scheduled (js->clj (.snapshot ^js agency) :keywordize-keys true)))))
      (js/setTimeout
       (fn []
         (try
           (is (some #(= "animation.removeSnippet" (:type %)) @(:events effects)))
           ((:unsubscribe effects))
           (.dispose ^js agency)
           (done)
           (catch :default error
             (.dispose ^js agency)
             (throw error))))
       130))))
