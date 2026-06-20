(ns polymer.character
  (:require [polymer.animation.agency :as animation]
            [polymer.blink.agency :as blink]
            [polymer.stream :as stream]
            [polymer.vocal.agency :as vocal]))

;; A character is a network of Polymer agencies.
;;
;; LoomLarge may still consume streams from legacy Latticework services during
;; the migration, but it should not consume Polymer animation events and turn
;; them into animation calls. Inside Polymer, Blink emits animation intent,
;; this network routes that intent to Animation, and Animation talks directly to
;; the Loom3/Embody animation runtime.

(def fast-blink-prosodic-cooldown-ms 1200)

(defn fast-blink-prosodic-snippet [now]
  ;; Fast blinking gets a small downward head cue. This lives in Polymer now so
  ;; LoomLarge does not need bespoke blink/prosody routing code.
  {:name (str "polymer:prosodic:blink-fast:" now)
   :curves {"1" [{:time 0 :intensity 0}
                 {:time 0.12 :intensity 0.26}
                 {:time 0.42 :intensity 0.34}
                 {:time 0.72 :intensity 0}]
            "2" [{:time 0 :intensity 0}
                 {:time 0.12 :intensity 0.2}
                 {:time 0.42 :intensity 0.28}
                 {:time 0.72 :intensity 0}]
            "54" [{:time 0 :intensity 0}
                  {:time 0.16 :intensity 0.16}
                  {:time 0.44 :intensity 0.2}
                  {:time 0.72 :intensity 0}]}
   :maxTime 0.72
   :loop false
   :snippetCategory "prosodic"
   :snippetPriority 35
   :snippetPlaybackRate 1
   :snippetIntensityScale 0.75
   :metadata {:agency "prosodic"
              :trigger "blink-fast"}})

(defn create-character-agencies [config]
  (let [input-stream (stream/create-stream)
        event-stream (stream/create-stream)
        effect-stream (stream/create-stream)
        emit-input (:emit input-stream)
        emit-event (:emit event-stream)
        emit-effect (:emit effect-stream)
        animation-agency (animation/create-animation-agency (when config (aget config "animation")))
        blink-agency (blink/create-blink-agency (when config (aget config "blink")))
        vocal-agency (vocal/create-vocal-agency (when config (aget config "vocal")))
        unsubscribers (atom [])
        disposed? (atom false)
        last-fast-blink-cue-at (atom 0)]
    (letfn [(track! [unsubscribe]
              (swap! unsubscribers conj unsubscribe))

            (schedule-animation! [source-agency snippet options]
              (.dispatch ^js animation-agency
                         (clj->js {:type "scheduleSnippet"
                                   :sourceAgency source-agency
                                   :snippet snippet
                                   :options options})))

            (schedule-fast-blink-cue! []
              (let [now (.now js/Date)]
                (when (>= (- now @last-fast-blink-cue-at) fast-blink-prosodic-cooldown-ms)
                  (reset! last-fast-blink-cue-at now)
                  (schedule-animation! "prosodic"
                                       (fast-blink-prosodic-snippet now)
                                       {:autoPlay true}))))

            (dispatch! [message]
              (when-not @disposed?
                (let [payload (js->clj message :keywordize-keys true)]
                  ;; Input is still observable for tests/workers, but the write
                  ;; path routes to agencies directly inside Polymer.
                  (emit-input {:type "command"
                               :agency (:agency payload)
                               :message payload})
                  (case (:agency payload)
                    "blink" (.dispatch ^js blink-agency (clj->js (:command payload)))
                    "animation" (.dispatch ^js animation-agency (clj->js (:command payload)))
                    "vocal" (.dispatch ^js vocal-agency (clj->js (:command payload)))
                    (emit-event {:type "error"
                                 :agency (or (:agency payload) "unknown")
                                 :message "Unknown Polymer agency"})))))

            (snapshot! []
              (clj->js {:blink (js->clj (.snapshot ^js blink-agency) :keywordize-keys true)
                        :vocal (js->clj (.snapshot ^js vocal-agency) :keywordize-keys true)
                        :animation (js->clj (.snapshot ^js animation-agency) :keywordize-keys true)}))

            (route-blink-event! [event]
              (case (:type event)
                "animation.requestScheduleSnippet"
                (schedule-animation! (:agency event) (:snippet event) (:options event))

                "signal"
                (when (and (= "blink" (:agency event))
                           (= "blink-fast" (:signal event)))
                  (schedule-fast-blink-cue!))

                nil))

            (route-vocal-event! [event]
              (case (:type event)
                "animation.requestScheduleSnippet"
                (schedule-animation! (:agency event) (:snippet event) (:options event))

                "animation.requestRemoveSnippet"
                (.dispatch ^js animation-agency
                           (clj->js {:type "removeSnippet"
                                     :sourceAgency (:agency event)
                                     :name (:name event)}))

                "animation.requestSeekSnippet"
                (.dispatch ^js animation-agency
                           (clj->js {:type "seekSnippet"
                                     :sourceAgency (:agency event)
                                     :name (:name event)
                                     :offsetSec (:offsetSec event)}))

                nil))]
      ;; Fan-in agency events to one character-level event stream for tests,
      ;; workers, and future non-React observers. LoomLarge does not need to
      ;; consume this stream for Polymer animation playback.
      (track! (.subscribeEvents ^js animation-agency
                                #(emit-event (js->clj % :keywordize-keys true))))
      (track! (.subscribeEffects ^js animation-agency
                                 #(emit-effect (js->clj % :keywordize-keys true))))
      (track! (.subscribeEvents ^js blink-agency
                                (fn [event]
                                  (let [payload (js->clj event :keywordize-keys true)]
                                    (route-blink-event! payload)
                                    (emit-event payload)))))
      (track! (.subscribeEffects ^js blink-agency
                                 #(emit-effect (js->clj % :keywordize-keys true))))
      (track! (.subscribeEvents ^js vocal-agency
                                (fn [event]
                                  (let [payload (js->clj event :keywordize-keys true)]
                                    (route-vocal-event! payload)
                                    (emit-event payload)))))
      (track! (.subscribeEffects ^js vocal-agency
                                 #(emit-effect (js->clj % :keywordize-keys true))))
      #js {:dispatch dispatch!
           :input (stream/writable-port input-stream dispatch!)
           :events (stream/readable-port event-stream)
           :effects (stream/readable-port effect-stream)
           :snapshot snapshot!
           :agency (fn [name]
                     (case name
                       "animation" animation-agency
                       "blink" blink-agency
                       "vocal" vocal-agency
                       nil))
           :subscribeInput (fn [listener] ((:subscribe input-stream) listener))
           :subscribeEvents (fn [listener] ((:subscribe event-stream) listener))
           :subscribeEffects (fn [listener] ((:subscribe effect-stream) listener))
           :subscribe (fn [listener] ((:subscribe event-stream) listener))
           :subscribeStatus (fn [listener] ((:subscribe event-stream) listener))
           :subscribeCommands (fn [listener] ((:subscribe effect-stream) listener))
           :dispose (fn []
                      (when-not @disposed?
                        (reset! disposed? true)
                        (doseq [unsubscribe @unsubscribers]
                          (unsubscribe))
                        (reset! unsubscribers [])
                        (.dispose ^js blink-agency)
                        (.dispose ^js vocal-agency)
                        (.dispose ^js animation-agency)
                        ((:dispose input-stream))
                        ((:dispose event-stream))
                        ((:dispose effect-stream))))})))
