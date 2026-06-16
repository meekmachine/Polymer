(ns polymer.animation.agency
  (:require [polymer.animation.state :as state]
            [polymer.stream :as stream]))

;; The Animation agency is Polymer's animation-side boundary.
;;
;; It does not play clips itself. Instead it receives animation commands from
;; other agencies, records local scheduling state, and emits host effects. In
;; LoomLarge today, those effects are interpreted into Latticework
;; AnimationService calls. Later, more of the runtime scheduler can move into
;; Polymer behind this same agency boundary.

(def cleanup-buffer-ms 50)

(defn create-animation-agency [_config]
  (let [input-stream (stream/create-stream)
        event-stream (stream/create-stream)
        effect-stream (stream/create-stream)
        state-atom (atom state/default-state)
        cleanup-timers (atom {})
        disposed? (atom false)
        emit-input (:emit input-stream)
        emit-event (:emit event-stream)
        emit-effect (:emit effect-stream)]
    (letfn [(clear-cleanup! [name]
              (when-let [timer (get @cleanup-timers name)]
                (js/clearTimeout timer)
                (swap! cleanup-timers dissoc name)))

            (clear-all-cleanups! []
              (doseq [[_ timer] @cleanup-timers]
                (js/clearTimeout timer))
              (reset! cleanup-timers {}))

            (emit-remove! [name source-agency reason]
              (when-not @disposed?
                (clear-cleanup! name)
                (let [removed-at (state/now-ms)]
                  (swap! state-atom state/record-remove name source-agency removed-at)
                  (emit-event {:type "animationSnippetRemoved"
                               :agency "animation"
                               :sourceAgency source-agency
                               :reason reason
                               :name name
                               :removedAt removed-at})
                  (emit-effect {:type "animation.removeSnippet"
                                :agency "animation"
                                :sourceAgency source-agency
                                :effectId name
                                :name name}))))

            (schedule-cleanup! [name snippet source-agency]
              ;; Non-looping snippets clean themselves up after their declared
              ;; duration. Looping or open-ended snippets must be removed by an
              ;; explicit command from the owning agency/host.
              (clear-cleanup! name)
              (when (not (:loop snippet))
                (when-let [duration-ms (state/snippet-duration-ms snippet)]
                  (when (pos? duration-ms)
                    (let [timer (js/setTimeout
                                 #(emit-remove! name source-agency "completed")
                                 (+ duration-ms cleanup-buffer-ms))]
                      (swap! cleanup-timers assoc name timer))))))

            (schedule-snippet! [payload]
              (let [source-agency (or (:sourceAgency payload) "unknown")
                    requested-at (state/now-ms)
                    fallback-name (str "polymer:animation:" requested-at)
                    snippet (assoc (:snippet payload) :name (state/snippet-name (:snippet payload) fallback-name))
                    options (or (:options payload) {})
                    name (:name snippet)]
                (swap! state-atom state/record-schedule snippet options source-agency requested-at)
                (emit-event {:type "animationSnippetScheduled"
                             :agency "animation"
                             :sourceAgency source-agency
                             :name name
                             :snippet snippet
                             :options options
                             :requestedAt requested-at})
                (emit-effect {:type "animation.scheduleSnippet"
                              :agency "animation"
                              :sourceAgency source-agency
                              :effectId name
                              :snippet snippet
                              :options options})
                (schedule-cleanup! name snippet source-agency)
                snippet))

            (dispatch! [command]
              (when-not @disposed?
                (let [payload (js->clj command :keywordize-keys true)
                      type (:type payload)]
                  (emit-input {:type "command"
                               :agency "animation"
                               :command payload})
                  (case type
                    "scheduleSnippet"
                    (if (:snippet payload)
                      (schedule-snippet! payload)
                      (emit-event {:type "error"
                                   :agency "animation"
                                   :message "Animation scheduleSnippet command requires a snippet"}))

                    "removeSnippet"
                    (if-let [name (:name payload)]
                      (emit-remove! name (or (:sourceAgency payload) "unknown") "requested")
                      (emit-event {:type "error"
                                   :agency "animation"
                                   :message "Animation removeSnippet command requires a name"}))

                    "clear"
                    (do
                      (doseq [name (keys (:scheduled @state-atom))]
                        (emit-remove! name (or (:sourceAgency payload) "unknown") "clear"))
                      (clear-all-cleanups!))

                    (emit-event {:type "error"
                                 :agency "animation"
                                 :message (str "Unknown Animation command: " type)})))))]
      #js {:dispatch dispatch!
           :snapshot (fn [] (state/visible-state @state-atom))
           :input (stream/writable-port input-stream dispatch!)
           :events (stream/readable-port event-stream)
           :effects (stream/readable-port effect-stream)
           :subscribeInput (fn [listener] ((:subscribe input-stream) listener))
           :subscribeEvents (fn [listener] ((:subscribe event-stream) listener))
           :subscribeEffects (fn [listener] ((:subscribe effect-stream) listener))
           :subscribe (fn [listener] ((:subscribe event-stream) listener))
           :subscribeStatus (fn [listener] ((:subscribe event-stream) listener))
           :subscribeCommands (fn [listener] ((:subscribe effect-stream) listener))
           :scheduleSnippet (fn [snippet options]
                              (dispatch! #js {:type "scheduleSnippet"
                                              :sourceAgency "direct"
                                              :snippet snippet
                                              :options options}))
           :removeSnippet (fn [name]
                            (dispatch! #js {:type "removeSnippet"
                                            :sourceAgency "direct"
                                            :name name}))
           :dispose (fn []
                      (when-not @disposed?
                        (reset! disposed? true)
                        (clear-all-cleanups!)
                        ((:dispose input-stream))
                        ((:dispose event-stream))
                        ((:dispose effect-stream))))})))
