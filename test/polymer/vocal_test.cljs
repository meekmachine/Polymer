(ns polymer.vocal-test
  (:require [cljs.test :refer [deftest is testing]]
            [polymer.core :as polymer]
            [polymer.vocal.azure :as azure]
            [polymer.vocal.snippet :as snippet]
            [polymer.vocal.tongue :as tongue]
            [polymer.vocal.visemes :as visemes]))

(defn collect [target subscribe-fn]
  (let [events (atom [])]
    {:events events
     :unsubscribe (subscribe-fn target #(swap! events conj (js->clj % :keywordize-keys true)))}))

(defn domain-events [agency]
  (collect agency (fn [target listener] (.subscribeEvents ^js target listener))))

(defn effect-events [agency]
  (collect agency (fn [target listener] (.subscribeEffects ^js target listener))))

(defn scheduled-snippets [events]
  (->> @(:events events)
       (keep (fn [event]
               (when (= "animation.requestScheduleSnippet" (:type event))
                 (:snippet event))))))

(defn max-intensity [curve]
  (reduce max 0 (map :intensity curve)))

(def jali-fixture-phrases
  ["what" "five" "pop man" "chess" "duke" "hello world"])

(defn build-text-fixture
  ([text] (build-text-fixture text nil))
  ([text config]
   (let [speech-rate (or (:speechRate config) 1)]
     (snippet/build-text-snippet text
                                 (visemes/text->visemes text speech-rate)
                                 (merge {:speechRate speech-rate
                                         :intensity 1
                                         :jawScale 1}
                                        config)))))

(defn channels-of-type [snippet target-type]
  (filter #(= target-type (get-in % [:target :type])) (:channels snippet)))

(defn channel-by-target [snippet target-type target-id]
  (some #(when (and (= target-type (get-in % [:target :type]))
                    (= target-id (get-in % [:target :id])))
           %)
        (:channels snippet)))

(defn jaw-channel [snippet]
  (channel-by-target snippet "au" 26))

(defn tongue-channel [snippet]
  (channel-by-target snippet "au" (js/parseInt tongue/tongue-up-au 10)))

(defn viseme-channel [snippet viseme-id]
  (channel-by-target snippet "viseme" viseme-id))

(defn channel-max-intensity [channel]
  (max-intensity (:keyframes channel)))

(defn sample-channel [channel time-sec]
  (snippet/sample-curve-at (:keyframes channel) time-sec))

(defn frames-in-range [channel start-sec end-sec]
  (filter #(and (>= (:time %) start-sec)
                (<= (:time %) end-sec))
          (:keyframes channel)))

(defn local-peak-count [channel]
  (let [frames (vec (:keyframes channel))]
    (count
     (filter identity
             (map-indexed
              (fn [index frame]
                (let [previous (get frames (dec index))
                      next-frame (get frames (inc index))]
                  (and previous
                       next-frame
                       (> (:intensity frame) (+ (:intensity previous) 0.025))
                       (> (:intensity frame) (+ (:intensity next-frame) 0.025)))))
              frames)))))

(defn total-keyframes [snippet]
  (reduce + 0 (map #(count (:keyframes %)) (:channels snippet))))

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
      (is (not (contains? snippet :snippetCategory)))
      (is (= 1 (:snippetPlaybackRate snippet)))
      (is (false? (:autoVisemeJaw snippet)))
      (is (seq (get-in snippet [:curves :26])))
      (is (some #(= "viseme" (get-in % [:target :type])) (:channels snippet)))
      (is (some #(and (= "au" (get-in % [:target :type]))
                      (= 26 (get-in % [:target :id])))
                (:channels snippet)))
      (is (= ["hello" "world"] (map :word (:wordTimings snapshot))))
      (is (:speaking snapshot))
      (is (= 1 (:scheduledCount snapshot)))
      (is (empty? @(:events effects))))
    ((:unsubscribe events))
    ((:unsubscribe effects))
    (.dispose ^js agency)))

(deftest web-speech-text-emits-lip-and-independent-jaw-channels
  (let [agency (polymer/createVocalAgency #js {:speechRate 1 :jawScale 1})
        events (domain-events agency)]
    (.dispatch ^js agency #js {:type "startText"
                               :text "hello world"
                               :source "webSpeech"})
    (let [snippet (first (scheduled-snippets events))
          channels (:channels snippet)
          lip-channels (filter #(= "viseme" (get-in % [:target :type])) channels)
          jaw-channel (some #(when (and (= "au" (get-in % [:target :type]))
                                        (= 26 (get-in % [:target :id])))
                               %)
                            channels)]
      (is (seq lip-channels))
      (is jaw-channel)
      (is (false? (:autoVisemeJaw snippet)))
      (is (not (contains? snippet :snippetCategory)))
      (is (some :jawActivation (visemes/text->visemes "hello world"))))
    ((:unsubscribe events))
    (.dispose ^js agency)))

(deftest web-speech-fallback-timeline-uses-speech-length-scale
  (let [agency (polymer/createVocalAgency #js {:speechRate 1})
        events (domain-events agency)
        text "The saddest aspect of life right now is that science gathers knowledge faster than society gathers wisdom."]
    (.dispatch ^js agency #js {:type "startText" :text text :source "webSpeech"})
    (let [snippet (first (scheduled-snippets events))
          snapshot (js->clj (.snapshot ^js agency) :keywordize-keys true)
          last-word (last (:wordTimings snapshot))]
      (is (> (:maxTime snippet) 6))
      (is (> (:endSec last-word) 6))
      (is (seq (get-in snippet [:curves :26]))))
    ((:unsubscribe events))
    (.dispose ^js agency)))

(deftest web-speech-short-function-word-phrases-are-not-compressed
  (let [agency (polymer/createVocalAgency #js {:speechRate 1})
        events (domain-events agency)
        text "This is a test of web speech lip sync over a whole phrase."]
    (.dispatch ^js agency #js {:type "startText" :text text :source "webSpeech"})
    (let [snippet (first (scheduled-snippets events))
          snapshot (js->clj (.snapshot ^js agency) :keywordize-keys true)
          last-word (last (:wordTimings snapshot))]
      (is (> (:maxTime snippet) 4.4))
      (is (> (:endSec last-word) 4.4))
      (is (= "phrase" (:word last-word))))
    ((:unsubscribe events))
    (.dispose ^js agency)))

(deftest jali-fixtures-produce-independent-channel-surface
  (doseq [phrase jali-fixture-phrases]
    (let [snippet (build-text-fixture phrase)
          lip-channels (channels-of-type snippet "viseme")
          jaw (jaw-channel snippet)]
      (testing phrase
        (is (seq lip-channels))
        (is jaw)
        (is (false? (:autoVisemeJaw snippet)))
        (is (not (contains? snippet :snippetCategory)))
        (is (every? #(= "viseme" (get-in % [:target :type])) lip-channels))
        (is (every? #(seq (:keyframes %)) (:channels snippet)))
        (is (<= (count (:channels snippet)) 8))
        (is (<= (apply max (map #(count (:keyframes %)) (:channels snippet))) 48))
        (is (<= (total-keyframes snippet) 160))))))

(deftest jali-fixtures-jaw-scale-zero-preserves-lips
  (doseq [phrase jali-fixture-phrases]
    (let [snippet (build-text-fixture phrase {:jawScale 0})]
      (testing phrase
        (is (seq (channels-of-type snippet "viseme")))
        (is (nil? (jaw-channel snippet)))))))

(deftest jali-bilabial-fixture-closes-lips-without-bilabial-jaw-activation
  (let [events (visemes/text->visemes "pop man")
        bilabials (filter #(contains? #{"P" "B" "M"} (:phoneme %)) events)
        snippet (build-text-fixture "pop man")
        bilabial-channel (viseme-channel snippet (:B_M_P visemes/canonical-visemes))]
    (is (seq bilabials))
    (is (every? #(<= (:jawActivation %) 0.001) bilabials))
    (is bilabial-channel)
    (is (>= (channel-max-intensity bilabial-channel) 0.95))
    (is (jaw-channel snippet))))

(deftest jali-sibilant-fixture-keeps-jaw-lower-than-open-vowels
  (let [sibilant-snippet (build-text-fixture "chess")
        open-vowel-snippet (build-text-fixture "duke")
        sibilant-jaw (jaw-channel sibilant-snippet)
        open-vowel-jaw (jaw-channel open-vowel-snippet)]
    (is sibilant-jaw)
    (is open-vowel-jaw)
    (is (<= (channel-max-intensity sibilant-jaw) 0.2))
    (is (>= (channel-max-intensity open-vowel-jaw) 0.5))))

(deftest jali-labiodental-fixture-is-distinct-from-bilabial-closure
  (let [five (build-text-fixture "five")
        pop-man (build-text-fixture "pop man")
        labiodental (viseme-channel five (:F_V visemes/canonical-visemes))
        bilabial-in-five (viseme-channel five (:B_M_P visemes/canonical-visemes))
        bilabial-in-pop (viseme-channel pop-man (:B_M_P visemes/canonical-visemes))]
    (is labiodental)
    (is (nil? bilabial-in-five))
    (is bilabial-in-pop)
    (is (>= (channel-max-intensity labiodental) 0.8))
    (is (>= (channel-max-intensity bilabial-in-pop) 0.95))
    (is (<= (channel-max-intensity (jaw-channel five)) 0.2))))

(deftest web-speech-diphthongs-expand-lip-travel-without-second-jaw-flap
  (let [events (visemes/text->visemes "choice")
        snippet (snippet/build-text-snippet "choice"
                                            events
                                            {:intensity 1 :jawScale 1})
        oh-channel (viseme-channel snippet (:Oh visemes/canonical-visemes))
        ee-channel (viseme-channel snippet (:EE visemes/canonical-visemes))
        jaw (jaw-channel snippet)
        diphthong-events (filter #(= "OY" (:phoneme %)) events)
        jaw-frames-during-diphthong (frames-in-range jaw 0.12 0.21)]
    (is (>= (count diphthong-events) 2))
    (is oh-channel)
    (is ee-channel)
    (is (>= (channel-max-intensity oh-channel) 0.85))
    (is (>= (channel-max-intensity ee-channel) 0.85))
    (is (<= (local-peak-count jaw) 1))
    (is (every? #(> (:intensity %) 0.20) jaw-frames-during-diphthong))))

(deftest web-speech-ey-diphthong-travels-from-ae-to-ee
  (let [events (visemes/text->visemes "say")
        snippet (snippet/build-text-snippet "say"
                                            events
                                            {:intensity 1 :jawScale 1})
        ae-channel (viseme-channel snippet (:AE visemes/canonical-visemes))
        ee-channel (viseme-channel snippet (:EE visemes/canonical-visemes))
        jaw (jaw-channel snippet)
        diphthong-events (filter #(= "EY" (:phoneme %)) events)]
    (is (= 2 (count diphthong-events)))
    (is (every? #(contains? (set (:phonemeClasses %)) "diphthong") diphthong-events))
    (is ae-channel)
    (is ee-channel)
    (is (>= (channel-max-intensity ae-channel) 0.75))
    (is (>= (channel-max-intensity ee-channel) 0.80))
    (is (<= (local-peak-count jaw) 1))))

(deftest vocal-jaw-planner-keeps-one-arc-through-provider-diphthong
  (let [snippet (snippet/build-vocal-snippet
                 [{:visemeId (:Oh visemes/canonical-visemes)
                   :phoneme "OW"
                   :phonemeClass "vowel"
                   :phonemeClasses ["vowel" "diphthong"]
                   :jawActivation 0.40
                   :offsetMs 0
                   :durationMs 92}
                  {:visemeId (:W_OO visemes/canonical-visemes)
                   :phoneme "OW"
                   :phonemeClass "glide"
                   :phonemeClasses ["glide" "lip-heavy" "diphthong"]
                   :jawActivation 0.34
                   :offsetMs 54
                   :durationMs 78}]
                 {:intensity 1 :jawScale 1}
                 "voice:diphthong")
        jaw (jaw-channel snippet)]
    (is jaw)
    (is (> (sample-channel jaw 0.06) 0.30))
    (is (> (sample-channel jaw 0.11) 0.30))
    (is (<= (local-peak-count jaw) 1))))

(deftest vocal-jaw-planner-collapses-stacked-consonants
  (let [snippet (build-text-fixture "texts")
        jaw (jaw-channel snippet)]
    (is jaw)
    ;; "texts" has a vowel followed by a K/S/T/S coda. The coda should narrow
    ;; or close around one low target, not produce multiple jaw-open beats.
    (is (>= (channel-max-intensity jaw) 0.12))
    (is (<= (sample-channel jaw 0.30) 0.16))
    (is (<= (sample-channel jaw 0.36) 0.16))
    (is (<= (local-peak-count jaw) 1))))

(deftest vocal-jaw-planner-keeps-stacked-consonants-from-reopening-jaw
  (let [snippet (build-text-fixture "strengths")
        jaw (jaw-channel snippet)
        events (visemes/text->visemes "strengths")
        coda-events (filter #(contains? #{"NG" "TH" "S"} (:phoneme %)) events)]
    (is (>= (count coda-events) 3))
    (is jaw)
    ;; The consonant stack after the vowel should stay as one low narrowing
    ;; target. The lip/tongue visemes still fire, but AU26 should not reopen
    ;; for NG, TH, and S as separate jaw beats.
    (is (<= (local-peak-count jaw) 1))
    (is (<= (sample-channel jaw 0.70) 0.16))
    (is (<= (sample-channel jaw 0.82) 0.16))))

(deftest vocal-tongue-planner-emits-independent-au-channel
  (let [snippet (build-text-fixture "tiny dog")
        tongue (tongue-channel snippet)
        jaw (jaw-channel snippet)]
    (is tongue)
    (is jaw)
    (is (= "au" (get-in tongue [:target :type])))
    (is (= 37 (get-in tongue [:target :id])))
    (is (>= (channel-max-intensity tongue) 0.45))
    (is (<= (local-peak-count tongue) 2))
    (is (seq (get-in snippet [:curves "37"])))))

(deftest vocal-tongue-planner-skips-lip-only-phrases
  (let [snippet (build-text-fixture "five pop")]
    (is (nil? (tongue-channel snippet)))
    (is (nil? (get-in snippet [:curves "37"])))))

(deftest vocal-tongue-planner-collapses-stacked-consonants
  (let [snippet (build-text-fixture "strengths")
        tongue (tongue-channel snippet)]
    (is tongue)
    ;; The initial STR cluster and the final NG/TH/S cluster should each read as
    ;; one tongue gesture, not separate flaps for every consonant in the stack.
    (is (>= (channel-max-intensity tongue) 0.45))
    (is (<= (local-peak-count tongue) 2))
    (is (<= (sample-channel tongue 0.70) 0.50))
    (is (<= (sample-channel tongue 0.82) 0.50))))

(deftest vocal-tongue-scale-zero-preserves-lips-and-jaw
  (let [snippet (build-text-fixture "tiny dog" {:tongueScale 0})]
    (is (seq (channels-of-type snippet "viseme")))
    (is (jaw-channel snippet))
    (is (nil? (tongue-channel snippet)))))

(deftest jali-text-fallback-events-carry-phoneme-class-metadata
  (let [events (visemes/text->visemes "five pop")
        f-event (some #(when (= "F" (:phoneme %)) %) events)
        p-event (some #(when (= "P" (:phoneme %)) %) events)
        vowel-event (some #(when (= "IH" (:phoneme %)) %) events)]
    (is (= "labiodental" (:phonemeClass f-event)))
    (is (contains? (set (:phonemeClasses f-event)) "fricative"))
    (is (= "bilabial" (:phonemeClass p-event)))
    (is (contains? (set (:phonemeClasses p-event)) "obstruent"))
    (is (= "vowel" (:phonemeClass vowel-event)))
    (is (= ["vowel"] (:phonemeClasses vowel-event)))))

(deftest jali-provider-timeline-normalization-preserves-class-metadata
  (let [normalized (snippet/normalize-events
                    [{:visemeId (:F_V visemes/canonical-visemes)
                      :phoneme "F"
                      :phonemeClass "labiodental"
                      :phonemeClasses ["labiodental" "fricative"]
                      :jawActivation 0.06
                      :offsetMs 0
                      :durationMs 80}])
        event (first normalized)]
    (is (= "F" (:phoneme event)))
    (is (= "labiodental" (:phonemeClass event)))
    (is (= ["labiodental" "fricative"] (:phonemeClasses event)))))

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

(deftest azure-visemes-carry-coarse-jali-class-metadata
  (let [timeline (azure/azure-visemes->timeline
                  [{:id 18 :time 0}
                   {:id 15 :time 0.16}
                   {:id 21 :time 0.32}]
                  600
                  {})
        f-event (some #(when (= (:F_V visemes/canonical-visemes) (:visemeId %)) %) timeline)
        s-event (some #(when (= (:S_Z visemes/canonical-visemes) (:visemeId %)) %) timeline)
        b-event (some #(when (= (:B_M_P visemes/canonical-visemes) (:visemeId %)) %) timeline)]
    (is (= "labiodental" (:phonemeClass f-event)))
    (is (contains? (set (:phonemeClasses f-event)) "fricative"))
    (is (= "sibilant" (:phonemeClass s-event)))
    (is (contains? (set (:phonemeClasses s-event)) "fricative"))
    (is (= "bilabial" (:phonemeClass b-event)))))

(deftest azure-expanded-diphthongs-carry-diphthong-class-metadata
  (let [timeline (azure/azure-visemes->timeline
                  [{:id 8 :time 0}]
                  180
                  {})
        oh-event (some #(when (= (:Oh visemes/canonical-visemes) (:visemeId %)) %) timeline)
        rounded-event (some #(when (= (:W_OO visemes/canonical-visemes) (:visemeId %)) %) timeline)]
    (is oh-event)
    (is rounded-event)
    (is (contains? (set (:phonemeClasses oh-event)) "diphthong"))
    (is (contains? (set (:phonemeClasses rounded-event)) "diphthong"))
    (is (>= (:jawActivation oh-event) 0.30))
    (is (>= (:jawActivation rounded-event) 0.30))))

(deftest vocal-snippet-limits-overlapping-lip-activation
  (let [built (snippet/build-vocal-snippet
               [{:visemeId (:B_M_P visemes/canonical-visemes) :offsetMs 0 :durationMs 120}
                {:visemeId (:Ah visemes/canonical-visemes) :offsetMs 0 :durationMs 120}]
               {:intensity 2 :jawScale 1}
               "voice:overlap")
        curves (:curves built)
        closure-key (str (:B_M_P visemes/canonical-visemes))
        secondary-key (str (:Ah visemes/canonical-visemes))
        closure-value (snippet/sample-curve-at (get curves closure-key) 0.004)
        secondary-value (snippet/sample-curve-at (get curves secondary-key) 0.004)]
    (is (>= closure-value 0.55))
    (is (<= secondary-value 0.04))))

(deftest vocal-jaw-activation-is-independent-from-viseme-morphs
  (let [built (snippet/build-vocal-snippet
               [{:visemeId (:Ah visemes/canonical-visemes)
                 :jawActivation 0
                 :offsetMs 0
                 :durationMs 120}
                {:visemeId (:Ah visemes/canonical-visemes)
                 :jawActivation 0.45
                 :offsetMs 220
                 :durationMs 120}]
               {:intensity 1 :jawScale 1}
               "voice:jaw-independent")
        lip-curve (get-in built [:curves "1"])
        jaw-curve (get-in built [:curves "26"])]
    (is (> (max-intensity lip-curve) 0.8))
    (is (<= (snippet/sample-curve-at jaw-curve 0.06) 0.001))
    (is (> (snippet/sample-curve-at jaw-curve 0.27) 0.4))))

(deftest vocal-jaw-scale-zero-keeps-lip-visemes-without-jaw-channel
  (let [built (snippet/build-vocal-snippet
               [{:visemeId (:Ah visemes/canonical-visemes)
                 :jawActivation 0.8
                 :offsetMs 0
                 :durationMs 120}]
               {:intensity 1 :jawScale 0}
               "voice:jaw-off")]
    (is (seq (get-in built [:curves "1"])))
    (is (nil? (get-in built [:curves "26"])))
    (is (not (some #(and (= "au" (get-in % [:target :type]))
                         (= 26 (get-in % [:target :id])))
                   (:channels built))))))

(deftest vocal-source-label-does-not-change-viseme-jaw-mixing
  (let [agency (polymer/createVocalAgency #js {:visualLeadMs 0})
        events (domain-events agency)
        canonical-visemes #js [#js {:visemeId 1 :offsetMs 100 :durationMs 180}
                               #js {:visemeId 14 :offsetMs 320 :durationMs 160}]]
    (.dispatch ^js agency
               #js {:type "startTimeline"
                    :timeline #js {:name "voice:web"
                                   :source "webSpeech"
                                   :visemes canonical-visemes}})
    (.dispatch ^js agency #js {:type "stop"})
    (.dispatch ^js agency
               #js {:type "startTimeline"
                    :timeline #js {:name "voice:azure"
                                   :source "azure"
                                   :visemes canonical-visemes}})
    (let [[web-snippet azure-snippet] (scheduled-snippets events)]
      (is (not (contains? web-snippet :snippetCategory)))
      (is (not (contains? azure-snippet :snippetCategory)))
      (is (= (:curves web-snippet) (:curves azure-snippet)))
      (is (= (:channels web-snippet) (:channels azure-snippet)))
      (is (= (get-in web-snippet [:curves :26])
             (get-in azure-snippet [:curves :26]))))
    ((:unsubscribe events))
    (.dispose ^js agency)))

(deftest vocal-visual-lead-is-applied-before-shared-snippet-build
  (let [agency (polymer/createVocalAgency #js {:visualLeadMs 50})
        events (domain-events agency)]
    (.dispatch ^js agency
               #js {:type "startTimeline"
                    :timeline #js {:name "voice:lead"
                                   :source "webSpeech"
                                   :visemes #js [#js {:visemeId 1 :offsetMs 100 :durationMs 180}]}})
    (let [snippet (first (scheduled-snippets events))
          lip-curve (get-in snippet [:curves :1])
          jaw-curve (get-in snippet [:curves :26])]
      (is (= 0.05 (:time (first lip-curve))))
      (is (= 0.05 (:time (first jaw-curve)))))
    ((:unsubscribe events))
    (.dispose ^js agency)))

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
    (is (= "visemeSnippet" (get-in (first @calls) [:options :snippetCategory])))
    (is (false? (get-in (first @calls) [:options :autoVisemeJaw])))
    (is (seq (get-in (first @calls) [:curves :1])))
    (is (seq (get-in (first @calls) [:curves :26])))
    (is (some #(and (= "au" (get-in % [:target :type]))
                    (= 26 (get-in % [:target :id])))
              (:channels
               (:snippet
                (some #(when (= "animationSnippetScheduled" (:type %)) %)
                      @(:events events))))))
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

(deftest web-speech-word-boundary-millisecond-clock-does-not-seek-to-end
  (let [calls (atom [])
        system (polymer/createCharacterAgencies #js {:animation #js {:runtime (make-runtime calls)}})]
    (.dispatch ^js system
               #js {:agency "vocal"
                    :command #js {:type "startText"
                                  :name "voice:webspeech-ms"
                                  :text "hello world"
                                  :source "webSpeech"}})
    ;; Some Web Speech implementations report boundary elapsedTime in a
    ;; millisecond-looking clock. Normalize that before drift correction so a
    ;; second-word boundary like 650 does not clamp the phrase snippet to its
    ;; end and make the lips move only once at the beginning.
    (.dispatch ^js system
               #js {:agency "vocal"
                    :command #js {:type "wordBoundary"
                                  :word "world"
                                  :wordIndex 1
                                  :observedElapsedSec 650}})
    (let [seek-call (some #(when (= "setSnippetTime" (:method %)) %) @calls)]
      (is seek-call)
      (is (> (:offsetSec seek-call) 0.64))
      (is (< (:offsetSec seek-call) 0.66)))
    (.dispose ^js system)))

(deftest web-speech-word-boundary-zero-clock-uses-host-elapsed-time
  (let [calls (atom [])
        system (polymer/createCharacterAgencies #js {:animation #js {:runtime (make-runtime calls)}})]
    (.dispatch ^js system
               #js {:agency "vocal"
                    :command #js {:type "startText"
                                  :name "voice:webspeech-zero-clock"
                                  :text "hello world"
                                  :source "webSpeech"}})
    ;; Some browser voices emit boundary events but keep elapsedTime at 0. The
    ;; host clock keeps those later boundaries from seeking the full phrase
    ;; snippet back to the beginning.
    (.dispatch ^js system
               #js {:agency "vocal"
                    :command #js {:type "wordBoundary"
                                  :word "world"
                                  :wordIndex 1
                                  :observedElapsedSec 0
                                  :hostElapsedSec 0.64}})
    (let [seek-call (some #(when (= "setSnippetTime" (:method %)) %) @calls)]
      (is seek-call)
      (is (> (:offsetSec seek-call) 0.63))
      (is (< (:offsetSec seek-call) 0.65)))
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
