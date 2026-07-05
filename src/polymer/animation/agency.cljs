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

(defn play-built-handle! [handle]
  (when-let [play (js-method handle "play")]
    (.call play handle))
  handle)

(defn engine->runtime [engine]
  ;; Loom3 already exposes the dynamic clip/snippet methods the scheduler needs.
  ;; Polymer adapts the current engine once here and keeps all later playback,
  ;; parameter, and cleanup calls inside the Animation agency.
  (let [runtime #js {:buildClip (fn [clip-name curves options]
                                  (call-js engine "buildClip" clip-name curves options))
                     :playSnippet (fn [clip-name curves options]
                                    (or
                                     (call-js engine "playSnippet" #js {:name clip-name :curves curves} options)
                                     (let [handle (call-js engine "buildClip" clip-name curves options)]
                                       (play-built-handle! handle))))
                     :updateClipParams (fn [clip-name params]
                                         (call-js engine "updateClipParams" clip-name params))
                     :setSnippetTime (fn [clip-name offset-sec]
                                       (or (call-js engine "setSnippetTime" clip-name offset-sec)
                                           (call-js engine "seekSnippet" clip-name offset-sec)
                                           (call-js engine "seek" clip-name offset-sec)))
                     :cleanupSnippet (fn [clip-name]
                                       (or (call-js engine "cleanupSnippet" clip-name)
                                           (call-js engine "stopAnimation" clip-name)))
                     :getAnimationState (fn [clip-name]
                                          (call-js engine "getAnimationState" clip-name))}]
    ;; Only expose typed playback if the underlying engine really has a typed
    ;; build/play entry point. Otherwise play-runtime-snippet! must use the legacy
    ;; curve fallback and add the viseme category hint instead of dropping the clip.
    (when (js-method engine "buildTypedClip")
      (aset runtime "buildTypedClip"
            (fn [clip-name channels options]
              (call-js engine "buildTypedClip" clip-name channels options))))
    (when (or (js-method engine "playTypedSnippet")
              (js-method engine "buildTypedClip"))
      (aset runtime "playTypedSnippet"
            (fn [snippet options]
              (let [clip-name (aget snippet "name")
                    channels (aget snippet "channels")]
                (or
                 (call-js engine "playTypedSnippet" snippet options)
                 (play-built-handle!
                  (call-js engine "buildTypedClip" clip-name channels options)))))))
    runtime))

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

(defn viseme-snippet-category? [category]
  (= "visemeSnippet" category))

(defn typed-channels [snippet]
  (let [channels (:channels snippet)]
    (when (seq channels)
      channels)))

(defn typed-channel-target [channel]
  (:target channel))

(defn typed-viseme-channel? [channel]
  (= "viseme" (:type (typed-channel-target channel))))

(defn typed-jaw-au-channel? [channel]
  (let [target (typed-channel-target channel)]
    (and (= "au" (:type target))
         (= 26 (:id target)))))

(defn typed-viseme-snippet? [snippet]
  (boolean (some typed-viseme-channel? (typed-channels snippet))))

(defn typed-viseme-curves-snippet? [snippet]
  (and (typed-viseme-snippet? snippet)
       (seq (:curves snippet))))

(defn typed-jaw-snippet? [snippet]
  (boolean (some typed-jaw-au-channel? (typed-channels snippet))))

(defn explicit-auto-viseme-jaw [snippet]
  (when (contains? snippet :autoVisemeJaw)
    (:autoVisemeJaw snippet)))

(defn snippet->clip-options-map
  ([snippet options] (snippet->clip-options-map snippet options false))
  ([snippet options legacy-fallback?]
   ;; Typed channels carry their namespace in the snippet data, so the normal
   ;; Embody path does not need snippetCategory. The only place this agency still
   ;; creates "visemeSnippet" is the legacy curve fallback, where older Embody
   ;; builds need a hint to treat numeric keys 0-14 as visemes instead of AUs.
  (let [category (:snippetCategory snippet)
        typed-viseme? (typed-viseme-snippet? snippet)
        viseme-category? (or (viseme-snippet-category? category) typed-viseme?)
        curves (or (:curves snippet) {})
        has-jaw-curve? (or (contains? curves "26") (typed-jaw-snippet? snippet))
        category-for-options (or category
                                 (when (and legacy-fallback? typed-viseme?)
                                   "visemeSnippet"))
        loop? (boolean (:loop snippet))
        loop-mode (or (:mixerLoopMode snippet) (if loop? "repeat" "once"))
        playback-rate (or (:snippetPlaybackRate snippet) 1)
        reverse? (boolean (:mixerReverse snippet))
        signed-rate (if reverse? (- playback-rate) playback-rate)
        intensity-scale (or (:snippetIntensityScale snippet) 1)
        auto-viseme-jaw (if (contains? snippet :autoVisemeJaw)
                          (explicit-auto-viseme-jaw snippet)
                          (when (and viseme-category? has-jaw-curve?) false))]
    (cond->
     (merge
      {:loop loop?
       :loopMode loop-mode
       :repeatCount (:mixerRepeatCount snippet)
       :reverse reverse?
       :priority (:snippetPriority snippet)
       :playbackRate signed-rate
       :rate signed-rate
       :weight intensity-scale
       :mixerWeight (:mixerWeight snippet)
       :intensityScale intensity-scale
       :balance (or (:snippetBalance snippet) 0)
       :balanceMap (or (:snippetBalanceMap snippet) {})
       :jawScale (or (:snippetJawScale snippet) 1)
       :source "snippet"}
      options)
      category-for-options (assoc :snippetCategory category-for-options)
      (some? auto-viseme-jaw) (assoc :autoVisemeJaw auto-viseme-jaw)))))

(defn snippet->clip-options [snippet options]
  (clj->js (snippet->clip-options-map snippet options false)))

(defn snippet->legacy-clip-options [snippet options]
  (clj->js (snippet->clip-options-map snippet options true)))

(defn typed-snippet-js [name channels]
  (clj->js {:name name :channels channels}))

(defn play-runtime-typed-snippet! [runtime snippet options]
  (let [name (:name snippet)
        channels (typed-channels snippet)
        channels-js (clj->js channels)
        clip-options (snippet->clip-options snippet options)
        snippet-js (typed-snippet-js name channels)]
    (if (false? (:autoPlay options))
      (or (call-js runtime "buildTypedClip" name channels-js clip-options)
          (call-js runtime "playTypedSnippet" snippet-js clip-options))
      (or (call-js runtime "playTypedSnippet" snippet-js clip-options)
          (play-built-handle!
           (call-js runtime "buildTypedClip" name channels-js clip-options))))))

(defn play-runtime-legacy-snippet! [runtime snippet options]
  (when-let [curves (:curves snippet)]
    (let [name (:name snippet)
          curves-js (clj->js curves)
          clip-options (snippet->legacy-clip-options snippet options)]
      (if (false? (:autoPlay options))
        (or (call-js runtime "buildClip" name curves-js clip-options)
            (call-js runtime "playSnippet" name curves-js clip-options))
        (call-js runtime "playSnippet" name curves-js clip-options)))))

(defn play-runtime-snippet! [runtime snippet options]
  ;; Typed channels are Polymer's canonical animation contract. They say
  ;; "viseme 7", "AU26", or "bone HEAD rx" directly, so Embody does not have to
  ;; guess whether numeric curve keys are viseme slots or AU ids. The legacy
  ;; curve fallback is kept only for older runtimes that do not expose typed
  ;; playback yet; once a runtime accepts typed snippets, a failed typed build
  ;; should surface as an error instead of falling back to a route that can play
  ;; lip ids as unrelated facial AUs.
  (if (typed-channels snippet)
    (if (or (js-method runtime "playTypedSnippet")
            (js-method runtime "buildTypedClip"))
      (play-runtime-typed-snippet! runtime snippet options)
      (when (seq (:curves snippet))
        (play-runtime-legacy-snippet! runtime snippet options)))
    (play-runtime-legacy-snippet! runtime snippet options)))

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

            (seek-runtime! [name offset-sec]
              ;; Embody/Loom3 and test doubles may expose seeking at different
              ;; levels while that API settles. Try the active clip handle first,
              ;; then runtime-level methods, then updateClipParams as the broad
              ;; compatibility path. The command still remains owned by
              ;; Animation either way.
              (let [handle (get @handles name)
                    normalized-offset (max 0 (or offset-sec 0))]
                (or (when handle
                      (or (call-js handle "setTime" normalized-offset)
                          (call-js handle "seek" normalized-offset)))
                    (when runtime
                      (or (call-js runtime "setSnippetTime" name normalized-offset)
                          (call-js runtime "seekSnippet" name normalized-offset)
                          (call-js runtime "seek" name normalized-offset)
                          (call-js runtime "updateClipParams"
                                   name
                                   (clj->js {:time normalized-offset
                                             :offsetSec normalized-offset})))))))

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

            (seek-snippet! [payload]
              (let [source-agency (or (:sourceAgency payload) "unknown")
                    name (:name payload)
                    offset-sec (:offsetSec payload)]
                (cond
                  (not name)
                  (emit-event {:type "error"
                               :agency "animation"
                               :message "Animation seekSnippet command requires a name"})

                  (not (number? offset-sec))
                  (emit-event {:type "error"
                               :agency "animation"
                               :message "Animation seekSnippet command requires offsetSec"})

                  (seek-runtime! name offset-sec)
                  (let [seeked-at (state/now-ms)]
                    (swap! state-atom state/record-seek name source-agency seeked-at (max 0 offset-sec))
                    (emit-event {:type "animationSnippetSeeked"
                                 :agency "animation"
                                 :sourceAgency source-agency
                                 :name name
                                 :offsetSec (max 0 offset-sec)
                                 :seekedAt seeked-at}))

                  :else
                  (emit-event {:type "error"
                               :agency "animation"
                               :message (str "Animation runtime could not seek " name)}))))

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

                    "seekSnippet"
                    (seek-snippet! payload)

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
           :seekSnippet (fn [name offset-sec]
                          (dispatch! #js {:type "seekSnippet"
                                          :sourceAgency "direct"
                                          :name name
                                          :offsetSec offset-sec}))
           :dispose (fn []
                      (when-not @disposed?
                        (reset! disposed? true)
                        (doseq [name (keys @handles)]
                          (cleanup-runtime! name))
                        (clear-all-cleanups!)
                        ((:dispose input-stream))
                        ((:dispose event-stream))
                        ((:dispose effect-stream))))})))
