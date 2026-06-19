(ns polymer.animation.agency
  (:require [polymer.animation.state :as state]
            [polymer.stream :as stream]))

;; The Animation agency is the only Polymer agency that is allowed to touch the
;; Loom3/Embody animation runtime. Blink, prosody, lipsync, and future agencies
;; should send animation intent to this agency; they should not call the engine
;; and LoomLarge should not translate Polymer animation commands.

(def cleanup-buffer-ms 50)

(defn js-callable? [value]
  (= "function" (goog/typeOf value)))

(defn js-method [target name]
  (when target
    (let [method (aget target name)]
      (when (js-callable? method)
        method))))

(defn call-js [target name & args]
  (when-let [method (js-method target name)]
    (.apply method target (to-array args))))

(defn engine->runtime [engine]
  ;; Loom3 already exposes the dynamic clip/snippet methods the scheduler needs.
  ;; Polymer adapts the current engine once here and keeps all later playback,
  ;; parameter, and cleanup calls inside the Animation agency.
  #js {:buildClip (fn [clip-name curves options]
                    (call-js engine "buildClip" clip-name curves options))
       :playSnippet (fn [clip-name curves options]
                      (or
                       (call-js engine "playSnippet" #js {:name clip-name :curves curves} options)
                       (let [handle (call-js engine "buildClip" clip-name curves options)]
                         (when-let [play (js-method handle "play")]
                           (.call play handle))
                         handle)))
       :updateClipParams (fn [clip-name params]
                           (call-js engine "updateClipParams" clip-name params))
       :cleanupSnippet (fn [clip-name]
                         (or (call-js engine "cleanupSnippet" clip-name)
                             (call-js engine "stopAnimation" clip-name)))
       :getAnimationState (fn [clip-name]
                            (call-js engine "getAnimationState" clip-name))})

(defn config->runtime [config]
  (let [runtime (aget config "runtime")
        engine (aget config "engine")]
    (cond
      runtime runtime
      ;; When LoomLarge passes the live Loom3/Embody engine, the Polymer
      ;; Animation agency owns direct engine calls through this runtime-shaped
      ;; adapter. LoomLarge is only providing the dependency, not interpreting
      ;; animation stream data.
      engine (engine->runtime engine)
      :else nil)))

(defn snippet->clip-options [snippet options]
  (clj->js (merge
            {:loop (boolean (:loop snippet))
             :loopMode (if (:loop snippet) "repeat" "once")
             :priority (:snippetPriority snippet)
             :playbackRate (:snippetPlaybackRate snippet)
             :rate (:snippetPlaybackRate snippet)
             :weight (:snippetIntensityScale snippet)
             :snippetCategory (:snippetCategory snippet)
             :source "snippet"}
            options)))

(defn play-runtime-snippet! [runtime snippet options]
  (let [name (:name snippet)
        curves (clj->js (:curves snippet))
        clip-options (snippet->clip-options snippet options)]
    (if (false? (:autoPlay options))
      (or (call-js runtime "buildClip" name curves clip-options)
          (call-js runtime "playSnippet" name curves clip-options))
      (call-js runtime "playSnippet" name curves clip-options))))

(defn create-animation-agency [config]
  (let [runtime (config->runtime (or config #js {}))
        input-stream (stream/create-stream)
        event-stream (stream/create-stream)
        effect-stream (stream/create-stream)
        state-atom (atom state/default-state)
        cleanup-timers (atom {})
        handles (atom {})
        disposed? (atom false)
        emit-input (:emit input-stream)
        emit-event (:emit event-stream)]
    (letfn [(clear-cleanup! [name]
              (when-let [timer (get @cleanup-timers name)]
                (js/clearTimeout timer)
                (swap! cleanup-timers dissoc name)))

            (clear-all-cleanups! []
              (doseq [[_ timer] @cleanup-timers]
                (js/clearTimeout timer))
              (reset! cleanup-timers {}))

            (cleanup-runtime! [name]
              (when-let [handle (get @handles name)]
                (when-let [stop (js-method handle "stop")]
                  (.call stop handle)))
              (swap! handles dissoc name)
              (when runtime
                (call-js runtime "cleanupSnippet" name)))

            (emit-remove! [name source-agency reason]
              (when (and (not @disposed?)
                         (get-in @state-atom [:scheduled name]))
                (clear-cleanup! name)
                (cleanup-runtime! name)
                (let [removed-at (state/now-ms)]
                  (swap! state-atom state/record-remove name source-agency removed-at reason)
                  (emit-event {:type "animationSnippetRemoved"
                               :agency "animation"
                               :sourceAgency source-agency
                               :reason reason
                               :name name
                               :removedAt removed-at}))))

            (schedule-cleanup! [name snippet source-agency handle]
              ;; Prefer the Loom3 handle lifecycle when it is available. The
              ;; timer remains as a fallback so non-looping CLJS-authored
              ;; snippets still clean themselves up with simple mock runtimes.
              (clear-cleanup! name)
              (when-let [finished (aget handle "finished")]
                (when (js-callable? (aget finished "then"))
                  (.then finished
                         #(emit-remove! name source-agency "completed")
                         (fn [_error] nil))))
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
                    options (assoc (or (:options payload) {}) :sourceAgency source-agency)
                    name (:name snippet)]
                (swap! state-atom state/record-schedule snippet options source-agency requested-at)
                (emit-event {:type "animationSnippetScheduled"
                             :agency "animation"
                             :sourceAgency source-agency
                             :name name
                             :snippet snippet
                             :options options
                             :requestedAt requested-at})
                (if runtime
                  (if-let [handle (play-runtime-snippet! runtime snippet options)]
                    (do
                      (swap! handles assoc name handle)
                      (swap! state-atom state/record-start name source-agency (state/now-ms))
                      (emit-event {:type "animationSnippetStarted"
                                   :agency "animation"
                                   :sourceAgency source-agency
                                   :name name})
                      (schedule-cleanup! name snippet source-agency handle))
                    (emit-event {:type "error"
                                 :agency "animation"
                                 :message (str "Loom3 runtime did not return a clip handle for " name)}))
                  (emit-event {:type "error"
                               :agency "animation"
                               :message "Animation agency requires a Loom3 animation runtime or engine"}))
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
           ;; There is intentionally no host-effect contract here anymore.
           ;; Kept as an empty stream-compatible port so older tests/consumers
           ;; can unsubscribe safely while LoomLarge moves to direct runtime use.
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
                        (doseq [name (keys @handles)]
                          (cleanup-runtime! name))
                        (clear-all-cleanups!)
                        ((:dispose input-stream))
                        ((:dispose event-stream))
                        ((:dispose effect-stream))))})))
