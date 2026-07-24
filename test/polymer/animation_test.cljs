(ns polymer.animation-test
  (:require [cljs.test :refer [async deftest is]]
            [polymer.animation.domain :as domain]
            [polymer.animation.planner :as planner]
            [polymer.core :as polymer]))

(defn collect [target subscribe-fn]
  (let [events (atom [])]
    {:events events
     :unsubscribe (subscribe-fn target #(swap! events conj (js->clj % :keywordize-keys true)))}))

(defn domain-events [agency]
  (collect agency (fn [target listener] (.subscribeEvents ^js target listener))))

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
       :playTypedSnippet
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

(defn make-legacy-runtime [calls]
  #js {:playSnippet
       (fn [name curves options]
         (swap! calls conj {:method "playSnippet"
                            :name name
                            :curves (js->clj curves :keywordize-keys true)
                            :options (js->clj options :keywordize-keys true)})
         #js {:clipName name
              :stop (fn [] (swap! calls conj {:method "stop" :name name}))
              :finished (js/Promise.resolve nil)})
       :cleanupSnippet
       (fn [name]
         (swap! calls conj {:method "cleanupSnippet" :name name})
         true)})

(defn make-engine [calls]
  #js {:playSnippet
       (fn [snippet options]
         (swap! calls conj {:method "engine.playSnippet"
                            :name (aget snippet "name")
                            :curves (js->clj (aget snippet "curves") :keywordize-keys true)
                            :options (js->clj options :keywordize-keys true)})
         #js {:clipName (aget snippet "name")
              :stop (fn [] true)
              :finished (js/Promise.resolve nil)})
       :playTypedSnippet
       (fn [snippet options]
         (swap! calls conj {:method "engine.playTypedSnippet"
                            :name (aget snippet "name")
                            :channels (js->clj (aget snippet "channels") :keywordize-keys true)
                            :options (js->clj options :keywordize-keys true)})
         #js {:clipName (aget snippet "name")
              :stop (fn [] true)
              :finished (js/Promise.resolve nil)})
       :cleanupSnippet
       (fn [name]
         (swap! calls conj {:method "engine.cleanupSnippet" :name name})
         true)})

(defn make-legacy-engine [calls]
  #js {:playSnippet
       (fn [snippet options]
         (swap! calls conj {:method "engine.playSnippet"
                            :name (aget snippet "name")
                            :curves (js->clj (aget snippet "curves") :keywordize-keys true)
                            :options (js->clj options :keywordize-keys true)})
         #js {:clipName (aget snippet "name")
              :stop (fn [] true)
              :finished (js/Promise.resolve nil)})
       :cleanupSnippet
       (fn [name]
         (swap! calls conj {:method "engine.cleanupSnippet" :name name})
         true)})

(defn make-persistent-runtime [calls]
  #js {:playSnippet
       (fn [name curves options]
         (swap! calls conj {:method "playSnippet"
                            :name name
                            :curves (js->clj curves :keywordize-keys true)
                            :options (js->clj options :keywordize-keys true)})
         #js {:clipName name
              :stop (fn [] (swap! calls conj {:method "stop" :name name}))})
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

(deftest animation-planner-normalizes-command-data
  (let [actions (planner/plan-command
                 {}
                 {:type "scheduleSnippet"
                  :sourceAgency "blink"
                  :snippet {:name ""
                            :curves {"43" [{:time 0 :intensity 0}]}}
                  :options {:autoPlay true}}
                 1234)
        action (first actions)]
    (is (= :schedule (:op action)))
    (is (= "blink" (:sourceAgency action)))
    (is (= "polymer:animation:1234" (get-in action [:snippet :name])))
    (is (= "blink" (get-in action [:options :sourceAgency]))))
  (is (= :error
         (:op (first (planner/plan-command {} {:type "scheduleSnippet"} 1)))))
  (is (= :update
         (:op (first (planner/plan-command {}
                                            {:type "updateSnippet"
                                             :name "clip"
                                             :params {:weight 0.5}}
                                            1))))))

(deftest animation-agency-owns-schedule-and-calls-runtime
  (async done
         (let [calls (atom [])
               agency (polymer/createAnimationAgency #js {:runtime (make-runtime calls)})
               events (domain-events agency)]
           (.dispatch ^js agency #js {:type "scheduleSnippet"
                                      :sourceAgency "test"
                                      :snippet #js {:name "test:blink"
                                                    :curves #js {"43" #js [#js {:time 0 :intensity 0}
                                                                           #js {:time 0.05 :intensity 1}
                                                                           #js {:time 0.1 :intensity 0}]}
                                                    :maxTime 0.05
                                                    :loop false
                                                    :snippetCategory "test"
                                                    :snippetPriority 1}
                                      :options #js {:autoPlay true}})
           (is (= "playSnippet" (:method (first @calls))))
           (is (= "test:blink" (:name (first @calls))))
           (is (= "animationSnippetScheduled" (:type (first @(:events events)))))
           (is (= "animationSnippetStarted" (:type (second @(:events events)))))
           (is (some (fn [entry] (= "test:blink" (:name entry)))
                     (vals (:scheduled (js->clj (.snapshot ^js agency) :keywordize-keys true)))))
           (js/setTimeout
            (fn []
              (try
                (is (some #(= "animationSnippetRemoved" (:type %)) @(:events events)))
                (is (some #(= "cleanupSnippet" (:method %)) @calls))
                ((:unsubscribe events))
                (.dispose ^js agency)
                (done)
                (catch :default error
                  (.dispose ^js agency)
                  (throw error))))
            130))))

(deftest animation-scheduler-replaces-active-snippet-by-name
  (let [calls (atom [])
        agency (polymer/createAnimationAgency #js {:runtime (make-persistent-runtime calls)})
        events (domain-events agency)
        snippet #js {:name "replace:blink"
                     :curves #js {"43" #js [#js {:time 0 :intensity 0}
                                             #js {:time 0.05 :intensity 1}]}
                     :loop true
                     :snippetCategory "blink"}]
    (.dispatch ^js agency #js {:type "scheduleSnippet"
                               :sourceAgency "blink"
                               :snippet snippet
                               :options #js {:autoPlay true}})
    (.dispatch ^js agency #js {:type "scheduleSnippet"
                               :sourceAgency "blink"
                               :snippet snippet
                               :options #js {:autoPlay true}})
    (let [methods (map :method @calls)
          removed (some #(when (= "animationSnippetRemoved" (:type %)) %)
                        @(:events events))
          snapshot (js->clj (.snapshot ^js agency) :keywordize-keys true)]
      (is (= ["playSnippet" "stop" "cleanupSnippet" "playSnippet"] methods))
      (is (= "replaced" (:reason removed)))
      (is (contains? (:scheduled snapshot) :replace:blink))
      (is (= 2 (:scheduledCount snapshot)))
      (is (= 1 (:removedCount snapshot))))
    ((:unsubscribe events))
    (.dispose ^js agency)))

(deftest animation-update-request-stays-inside-animation-runtime-boundary
  (let [calls (atom [])
        agency (polymer/createAnimationAgency #js {:runtime (make-persistent-runtime calls)})
        events (domain-events agency)]
    (.dispatch ^js agency #js {:type "scheduleSnippet"
                               :sourceAgency "test"
                               :snippet #js {:name "update:clip"
                                             :curves #js {"1" #js [#js {:time 0 :intensity 0}
                                                                   #js {:time 0.1 :intensity 1}]}
                                             :loop true}
                               :options #js {:autoPlay true}})
    (.dispatch ^js agency #js {:type "updateSnippet"
                               :sourceAgency "test"
                               :name "update:clip"
                               :params #js {:weight 0.35 :playbackRate 1.5}})
    (let [update-call (some #(when (= "updateClipParams" (:method %)) %) @calls)
          update-event (some #(when (= "animationSnippetUpdated" (:type %)) %)
                             @(:events events))
          snapshot (js->clj (.snapshot ^js agency) :keywordize-keys true)]
      (is (= {:weight 0.35 :playbackRate 1.5} (:params update-call)))
      (is (= "update:clip" (:name update-event)))
      (is (= {:weight 0.35 :playbackRate 1.5}
             (get-in snapshot [:scheduled :update:clip :lastUpdateParams]))))
    ((:unsubscribe events))
    (.dispose ^js agency)))

(deftest animation-routes-lipsync-snippets-through-typed-runtime-when-available
  (let [calls (atom [])
        agency (polymer/createAnimationAgency #js {:runtime (make-runtime calls)})]
    (.dispatch ^js agency #js {:type "scheduleSnippet"
                               :sourceAgency "lipSync"
                               :snippet #js {:name "voice:webspeech"
                                             :curves #js {"1" #js [#js {:time 0 :intensity 0}
                                                                   #js {:time 0.08 :intensity 1}]
                                                          "103" #js [#js {:time 0 :intensity 0}
                                                                     #js {:time 0.08 :intensity 0.35}]}
                                             :channels #js [#js {:target #js {:type "viseme" :id 1}
                                                                 :keyframes #js [#js {:time 0 :intensity 0}
                                                                                 #js {:time 0.08 :intensity 1}]}
                                                            #js {:target #js {:type "lipSync" :id 103}
                                                                 :keyframes #js [#js {:time 0 :intensity 0}
                                                                                 #js {:time 0.08 :intensity 0.35}]}]
                                             :maxTime 0.08
                                             :loop false
                                             :snippetIntensityScale 0.8
                                             :snippetJawScale 1.25
                                             :autoVisemeJaw false}
                               :options #js {:autoPlay true}})
    (let [call (first @calls)
          options (:options call)]
      (is (= "playTypedSnippet" (:method call)))
      (is (= "viseme" (get-in call [:channels 0 :target :type])))
      (is (= 1 (get-in call [:channels 0 :target :id])))
      (is (= "lipSync" (get-in call [:channels 1 :target :type])))
      (is (= 103 (get-in call [:channels 1 :target :id])))
      (is (not (contains? options :snippetCategory)))
      (is (= 0.8 (:intensityScale options)))
      (is (= 0.8 (:weight options)))
      (is (= 1.25 (:jawScale options)))
      (is (false? (:autoVisemeJaw options))))
    (.dispose ^js agency)))

(deftest animation-adapts-engine-backed-lipsync-snippets-through-embody-typed-api
  (let [calls (atom [])
        agency (polymer/createAnimationAgency #js {:engine (make-engine calls)})]
    (.dispatch ^js agency #js {:type "scheduleSnippet"
                               :sourceAgency "lipSync"
                               :snippet #js {:name "voice:engine"
                                             :curves #js {"1" #js [#js {:time 0 :intensity 0}
                                                                   #js {:time 0.08 :intensity 1}]
                                                          "103" #js [#js {:time 0 :intensity 0}
                                                                     #js {:time 0.08 :intensity 0.35}]}
                                             :channels #js [#js {:target #js {:type "viseme" :id 1}
                                                                 :keyframes #js [#js {:time 0 :intensity 0}
                                                                                 #js {:time 0.08 :intensity 1}]}
                                                            #js {:target #js {:type "lipSync" :id 103}
                                                                 :keyframes #js [#js {:time 0 :intensity 0}
                                                                                 #js {:time 0.08 :intensity 0.35}]}]
                                             :maxTime 0.08
                                             :loop false
                                             :autoVisemeJaw false}
                               :options #js {:autoPlay true}})
    (let [call (first @calls)
          options (:options call)]
      (is (= "engine.playTypedSnippet" (:method call)))
      (is (= "viseme" (get-in call [:channels 0 :target :type])))
      (is (= "lipSync" (get-in call [:channels 1 :target :type])))
      (is (not (contains? options :snippetCategory)))
      (is (false? (:autoVisemeJaw options))))
    (.dispose ^js agency)))

(deftest animation-engine-wrapper-falls-back-to-legacy-only-when-engine-has-no-typed-api
  (let [calls (atom [])
        agency (polymer/createAnimationAgency #js {:engine (make-legacy-engine calls)})]
    (.dispatch ^js agency #js {:type "scheduleSnippet"
                               :sourceAgency "lipSync"
                               :snippet #js {:name "voice:legacy-engine"
                                             :curves #js {"1" #js [#js {:time 0 :intensity 0}
                                                                   #js {:time 0.08 :intensity 1}]
                                                          "103" #js [#js {:time 0 :intensity 0}
                                                                     #js {:time 0.08 :intensity 0.35}]}
                                             :channels #js [#js {:target #js {:type "viseme" :id 1}
                                                                 :keyframes #js [#js {:time 0 :intensity 0}
                                                                                 #js {:time 0.08 :intensity 1}]}
                                                            #js {:target #js {:type "lipSync" :id 103}
                                                                 :keyframes #js [#js {:time 0 :intensity 0}
                                                                                 #js {:time 0.08 :intensity 0.35}]}]
                                             :maxTime 0.08
                                             :loop false
                                             :autoVisemeJaw false}
                               :options #js {:autoPlay true}})
    (let [call (first @calls)
          options (:options call)]
      (is (= "engine.playSnippet" (:method call)))
      (is (= "visemeSnippet" (:snippetCategory options)))
      (is (false? (:autoVisemeJaw options))))
    (.dispose ^js agency)))

(deftest animation-routes-pure-typed-snippets-through-typed-runtime
  (let [calls (atom [])
        agency (polymer/createAnimationAgency #js {:runtime (make-runtime calls)})]
    (.dispatch ^js agency #js {:type "scheduleSnippet"
                               :sourceAgency "typed-test"
                               :snippet #js {:name "typed:only"
                                             :channels #js [#js {:target #js {:type "au" :id 45}
                                                                 :keyframes #js [#js {:time 0 :intensity 0}
                                                                                 #js {:time 0.08 :intensity 1}]}]
                                             :maxTime 0.08
                                             :loop false}
                               :options #js {:autoPlay true}})
    (let [call (first @calls)
          options (:options call)]
      (is (= "playTypedSnippet" (:method call)))
      (is (= "au" (get-in call [:channels 0 :target :type])))
      (is (= 45 (get-in call [:channels 0 :target :id])))
      (is (not (contains? options :snippetCategory))))
    (.dispose ^js agency)))

(deftest animation-synthesizes-viseme-category-only-for-legacy-runtime
  (let [calls (atom [])
        agency (polymer/createAnimationAgency #js {:runtime (make-legacy-runtime calls)})]
    (.dispatch ^js agency #js {:type "scheduleSnippet"
                               :sourceAgency "lipSync"
                               :snippet #js {:name "voice:legacy"
                                             :curves #js {"1" #js [#js {:time 0 :intensity 0}
                                                                   #js {:time 0.08 :intensity 1}]
                                                          "103" #js [#js {:time 0 :intensity 0}
                                                                     #js {:time 0.08 :intensity 0.35}]}
                                             :channels #js [#js {:target #js {:type "viseme" :id 1}
                                                                 :keyframes #js [#js {:time 0 :intensity 0}
                                                                                 #js {:time 0.08 :intensity 1}]}
                                                            #js {:target #js {:type "lipSync" :id 103}
                                                                 :keyframes #js [#js {:time 0 :intensity 0}
                                                                                 #js {:time 0.08 :intensity 0.35}]}]
                                             :maxTime 0.08
                                             :loop false
                                             :autoVisemeJaw false}
                               :options #js {:autoPlay true}})
    (let [options (:options (first @calls))]
      (is (= "playSnippet" (:method (first @calls))))
      (is (= "visemeSnippet" (:snippetCategory options)))
      (is (false? (:autoVisemeJaw options))))
    (.dispose ^js agency)))

(deftest animation-preserves-non-viseme-snippet-category
  (let [calls (atom [])
        agency (polymer/createAnimationAgency #js {:runtime (make-runtime calls)})]
    (.dispatch ^js agency #js {:type "scheduleSnippet"
                               :sourceAgency "blink"
                               :snippet #js {:name "blink:single"
                                             :curves #js {"43" #js [#js {:time 0 :intensity 0}
                                                                    #js {:time 0.04 :intensity 1}]}
                                             :maxTime 0.04
                                             :loop false
                                             :snippetCategory "blink"}
                               :options #js {:autoPlay true}})
    (let [options (:options (first @calls))]
      (is (= "blink" (:snippetCategory options)))
      (is (not (contains? options :autoVisemeJaw))))
    (.dispose ^js agency)))

(deftest animation-remaps-legacy-lipsync-jaw-au-26-to-control-103
  (let [sanitized (domain/sanitize-lipsync-jaw-snippet
                   {:name "voice:legacy-jaw"
                    :curves {"1" [{:time 0 :intensity 0} {:time 0.08 :intensity 1}]
                             "26" [{:time 0 :intensity 0} {:time 0.08 :intensity 0.4}]}
                    :channels [{:target {:type "viseme" :id 1}
                                :keyframes [{:time 0 :intensity 0} {:time 0.08 :intensity 1}]}
                               {:target {:type "au" :id 26}
                                :keyframes [{:time 0 :intensity 0} {:time 0.08 :intensity 0.4}]}]})
        calls (atom [])
        agency (polymer/createAnimationAgency #js {:runtime (make-runtime calls)})]
    (is (nil? (get-in sanitized [:curves "26"])))
    (is (seq (get-in sanitized [:curves "103"])))
    (is (false? (:autoVisemeJaw sanitized)))
    (is (= [{:type "viseme" :id 1} {:type "lipSync" :id 103}]
           (mapv :target (:channels sanitized))))
    (.dispatch ^js agency #js {:type "scheduleSnippet"
                               :sourceAgency "lipSync"
                               :snippet #js {:name "voice:legacy-jaw"
                                             :curves #js {"26" #js [#js {:time 0 :intensity 0}
                                                                    #js {:time 0.08 :intensity 0.4}]}
                                             :channels #js [#js {:target #js {:type "au" :id 26}
                                                                 :keyframes #js [#js {:time 0 :intensity 0}
                                                                                 #js {:time 0.08 :intensity 0.4}]}]
                                             :maxTime 0.08
                                             :loop false}
                               :options #js {:autoPlay true}})
    (let [call (first @calls)
          jaw-channel (first (:channels call))]
      (is (= "playTypedSnippet" (:method call)))
      (is (= "lipSync" (get-in jaw-channel [:target :type])))
      (is (= 103 (get-in jaw-channel [:target :id])))
      (is (false? (get-in call [:options :autoVisemeJaw])))
      (is (empty? (filter #(and (= "au" (get-in % [:target :type]))
                                (= 26 (get-in % [:target :id])))
                          (:channels call)))))
    (.dispose ^js agency)))
