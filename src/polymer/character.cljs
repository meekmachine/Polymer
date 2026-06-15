(ns polymer.character
  (:require [polymer.animation.agency :as animation]
            [polymer.blink.agency :as blink]
            [polymer.stream :as stream]))

;; A character agency system is the stable host-facing boundary.
;;
;; LoomLarge should create one of these per loaded character, then connect its
;; streams to React rendering, animation engines, storage, audio, network, and
;; other host-owned side effects. Individual React sections should not create
;; agencies or timers.

(defn create-character-agencies [config]
  (let [input (if config (js->clj config :keywordize-keys true) {})
        input-stream (stream/create-stream)
        state-stream (stream/create-stream)
        event-stream (stream/create-stream)
        effect-stream (stream/create-stream)
        emit-input (:emit input-stream)
        emit-state (:emit state-stream)
        emit-event (:emit event-stream)
        emit-effect (:emit effect-stream)
        animation-agency (animation/create-animation-agency (clj->js (:animation input)))
        blink-agency (blink/create-blink-agency (clj->js (:blink input)))
        unsubscribers (atom [])
        disposed? (atom false)]
    (letfn [(track! [unsubscribe]
              (swap! unsubscribers conj unsubscribe))

            (dispatch! [message]
              (when-not @disposed?
                (let [payload (js->clj message :keywordize-keys true)]
                  ;; Input commands are observable at the character boundary.
                  ;; That lets UI, backend, workers, and future agencies all use
                  ;; the same message shape.
                  (emit-input {:type "command"
                               :agency (:agency payload)
                               :message payload})
                  (case (:agency payload)
                    "blink" (.dispatch ^js blink-agency (clj->js (:command payload)))
                    "animation" (.dispatch ^js animation-agency (clj->js (:command payload)))
                    (emit-event {:type "error"
                                 :agency (or (:agency payload) "unknown")
                                 :message "Unknown Polymer agency"})))))

            (snapshot! []
              (clj->js {:blink (js->clj (.snapshot ^js blink-agency) :keywordize-keys true)
                        :animation (js->clj (.snapshot ^js animation-agency) :keywordize-keys true)}))

            (route-blink-event! [event]
              ;; Blink can request animation, but Animation owns the host-facing
              ;; scheduling effects. This is the first cross-agency route in the
              ;; Polymer character network and the pattern future agencies
              ;; should use instead of React component glue.
              (when (= "animation.requestScheduleSnippet" (:type event))
                (.dispatch ^js animation-agency
                           (clj->js {:type "scheduleSnippet"
                                     :sourceAgency (:agency event)
                                     :snippet (:snippet event)
                                     :options (:options event)}))))]
      ;; Fan-in agency outputs to character-level streams. As more agencies are
      ;; added, they should be wired here rather than in React components.
      (track! (.subscribeState ^js animation-agency
                               #(emit-state (js->clj % :keywordize-keys true))))
      (track! (.subscribeEvents ^js animation-agency
                                #(emit-event (js->clj % :keywordize-keys true))))
      (track! (.subscribeEffects ^js animation-agency
                                 #(emit-effect (js->clj % :keywordize-keys true))))
      (track! (.subscribeState ^js blink-agency
                               #(emit-state (js->clj % :keywordize-keys true))))
      (track! (.subscribeEvents ^js blink-agency
                                (fn [event]
                                  (let [payload (js->clj event :keywordize-keys true)]
                                    (route-blink-event! payload)
                                    (emit-event payload)))))
      (track! (.subscribeEffects ^js blink-agency
                                 #(emit-effect (js->clj % :keywordize-keys true))))
      #js {:dispatch dispatch!
           :input (stream/writable-port input-stream dispatch!)
           :state (stream/readable-port state-stream)
           :events (stream/readable-port event-stream)
           :effects (stream/readable-port effect-stream)
           :snapshot snapshot!
           :agency (fn [name]
                     (case name
                       "animation" animation-agency
                       "blink" blink-agency
                       nil))
           :subscribeInput (fn [listener] ((:subscribe input-stream) listener))
           :subscribeState (fn [listener] ((:subscribe state-stream) listener))
           :subscribeEvents (fn [listener] ((:subscribe event-stream) listener))
           :subscribeEffects (fn [listener] ((:subscribe effect-stream) listener))
           ;; Compatibility aliases for the current migration: old "status"
           ;; subscribers see state + events, and old "commands" subscribers see
           ;; effects.
           :subscribe (fn [listener] (stream/subscribe-many [state-stream event-stream] listener))
           :subscribeStatus (fn [listener] (stream/subscribe-many [state-stream event-stream] listener))
           :subscribeCommands (fn [listener] ((:subscribe effect-stream) listener))
           :dispose (fn []
                      (when-not @disposed?
                        (reset! disposed? true)
                        (doseq [unsubscribe @unsubscribers]
                          (unsubscribe))
                        (reset! unsubscribers [])
                        (.dispose ^js animation-agency)
                        (.dispose ^js blink-agency)
                        ((:dispose input-stream))
                        ((:dispose state-stream))
                        ((:dispose event-stream))
                        ((:dispose effect-stream))))})))
