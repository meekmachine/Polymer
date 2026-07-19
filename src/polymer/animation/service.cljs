(ns polymer.animation.service
  (:require [clojure.string :as str]
            [polymer.animation.agency :as animation-agency]))

;; This namespace is the JavaScript compatibility API for LoomLarge's existing
;; animation manager calls. It does not reimplement Latticework's runtime path:
;; snippet playback is normalized here as data and then dispatched into
;; Polymer's CLJS Animation agency, whose scheduler owns the Embody side effect.

(def snippet-categories
  #{"emotionAnimationsList"
    "speakingAnimationsList"
    "visemeAnimationsList"
    "eyeHeadTrackingAnimationsList"})

(def bundled-snippet-manifest-key "bundledAnimationSnippetsManifest")
(def bundled-snippet-version-key "bundledAnimationSnippetsVersion")

(defn now-ms []
  (let [performance (aget js/globalThis "performance")]
    (if (and performance (fn? (aget performance "now")))
      (.now performance)
      (.now js/Date))))

(defn finite-number? [value]
  (and (number? value) (js/isFinite value)))

(defn number-or [value fallback]
  (if (finite-number? value) value fallback))

(defn string-or [value fallback]
  (if (and (string? value) (not (str/blank? value))) value fallback))

(defn key->string [value]
  (cond
    (keyword? value) (name value)
    (string? value) value
    (nil? value) ""
    :else (str value)))

(defn value->clj [value]
  (cond
    (nil? value) nil
    (map? value) value
    :else (js->clj value :keywordize-keys true)))

(defn js-call [target method-name & args]
  (when target
    (when-let [method (aget target method-name)]
      (when (fn? method)
        (.apply method target (to-array args))))))

(defn subscription [unsubscribe]
  #js {:unsubscribe unsubscribe})

(defn subscribe! [listeners listener]
  (swap! listeners conj listener)
  (subscription (fn [] (swap! listeners disj listener))))

(defn emit-to! [listeners payload]
  (let [js-payload (clj->js payload)]
    (doseq [listener @listeners]
      (try
        (listener js-payload)
        (catch :default error
          (.error js/console "[Polymer Animation] stream listener failed" error))))))

(defn create-port [listeners]
  #js {:subscribe (fn [listener] (subscribe! listeners listener))})

(defn event-timestamp []
  (now-ms))

(defn emit-event! [event-listeners event]
  (emit-to! event-listeners (assoc event :timestamp (event-timestamp))))

(defn normalize-point [point]
  (let [source (or (value->clj point) {})
        time (number-or (:time source) (number-or (:t source) 0))
        intensity (number-or (:intensity source) (number-or (:v source) 0))]
    {:time time
     :intensity intensity
     :inherit (boolean (:inherit source))}))

(defn normalize-curve-points [points]
  (->> (or points [])
       (map normalize-point)
       (sort-by :time)
       vec))

(defn normalize-curves [curves]
  (let [source (or (value->clj curves) {})]
    (reduce-kv
     (fn [out k points]
       (assoc out (key->string k) (normalize-curve-points points)))
     {}
     source)))

(defn add-authoring-point [out point key-name]
  (let [source (or (value->clj point) {})
        curve-key (key->string (or (get source key-name) (:id source) (:key source)))]
    (if (str/blank? curve-key)
      out
      (update out curve-key (fnil conj []) (normalize-point source)))))

(defn authoring-curves [snippet]
  (let [mapped (-> {}
                   (#(reduce (fn [out point] (add-authoring-point out point :id))
                             %
                             (or (:au snippet) [])))
                   (#(reduce (fn [out point] (add-authoring-point out point :key))
                             %
                             (or (:viseme snippet) []))))]
    (reduce-kv
     (fn [out key points]
       (assoc out key (normalize-curve-points points)))
     {}
     mapped)))

(defn curve-duration [curves]
  (reduce
   (fn [max-time points]
     (if-let [last-point (last points)]
       (max max-time (number-or (:time last-point) 0))
       max-time))
   0
   (vals (or curves {}))))

(defn channel-duration [channels]
  (reduce
   (fn [max-time channel]
     (reduce
      (fn [inner-max keyframe]
        (max inner-max (number-or (:time keyframe) 0)))
      max-time
      (or (:keyframes channel) [])))
   0
   (or channels [])))

(defn normalize-channels [channels]
  (when (seq channels)
    (->> channels
         (map value->clj)
         (mapv (fn [channel]
                 (update channel :keyframes normalize-curve-points))))))

(defn normalize-loop-mode [snippet]
  (let [mode (:mixerLoopMode snippet)]
    (if (#{"repeat" "once" "pingpong"} mode)
      mode
      (if (:loop snippet) "repeat" "once"))))

(defn normalize-snippet [snippet]
  (let [source (or (value->clj snippet) {})
        now (now-ms)
        curves (if (:curves source)
                 (normalize-curves (:curves source))
                 (authoring-curves source))
        channels (normalize-channels (:channels source))
        duration (max (number-or (:duration source) 0)
                      (number-or (:maxTime source) 0)
                      (curve-duration curves)
                      (channel-duration channels))
        loop-mode (normalize-loop-mode source)
        name (string-or (:name source) (str "polymer:animation:" now))]
    (cond->
     (assoc source
            :name name
            :curves curves
            :isPlaying (boolean (:isPlaying source))
            :loop (not= loop-mode "once")
            :loopIteration (number-or (:loopIteration source) 0)
            :loopDirection (if (= (:loopDirection source) -1) -1 (if (:mixerReverse source) -1 1))
            :lastLoopTime (number-or (:lastLoopTime source) 0)
            :snippetPlaybackRate (number-or (:snippetPlaybackRate source) 1)
            :snippetIntensityScale (number-or (:snippetIntensityScale source) 1)
            :snippetBlendMode (if (= (:snippetBlendMode source) "additive") "additive" "replace")
            :snippetJawScale (number-or (:snippetJawScale source) 1)
            :snippetBalance (max -1 (min 1 (number-or (:snippetBalance source) 0)))
            :snippetBalanceMap (or (:snippetBalanceMap source) {})
            :snippetCategory (string-or (:snippetCategory source) "default")
            :snippetPriority (number-or (:snippetPriority source) 0)
            :snippetEasing (string-or (:snippetEasing source) "linear")
            :mixerLoopMode loop-mode
            :mixerRepeatCount (when (finite-number? (:mixerRepeatCount source))
                                (:mixerRepeatCount source))
            :mixerReverse (boolean (:mixerReverse source))
            :currentTime (number-or (:currentTime source) 0)
            :startWallTime (number-or (:startWallTime source) now)
            :duration duration
            :maxTime duration
            :cursor (or (:cursor source) {}))
      channels (assoc :channels channels)
      (contains? source :autoVisemeJaw) (assoc :autoVisemeJaw (boolean (:autoVisemeJaw source))))))

(defn snippet-ui-state [snippet]
  (let [loop-mode (normalize-loop-mode snippet)]
    {:name (:name snippet)
     :isPlaying (boolean (:isPlaying snippet))
     :loop (not= loop-mode "once")
     :loopMode loop-mode
     :reverse (boolean (:mixerReverse snippet))
     :repeatCount (:mixerRepeatCount snippet)
     :loopIteration (:loopIteration snippet)
     :loopDirection (:loopDirection snippet)
     :currentTime (number-or (:currentTime snippet) 0)
     :duration (number-or (:duration snippet) 0)
     :playbackRate (number-or (:snippetPlaybackRate snippet) 1)
     :intensityScale (number-or (:snippetIntensityScale snippet) 1)
     :blendMode (if (= (:snippetBlendMode snippet) "additive") "additive" "replace")
     :balance (number-or (:snippetBalance snippet) 0)
     :category (string-or (:snippetCategory snippet) "default")
     :easing (string-or (:snippetEasing snippet) "linear")}))

(defn clone-snippet [snippet]
  (assoc snippet
         :curves (reduce-kv (fn [out key points] (assoc out key (mapv identity points))) {} (:curves snippet))
         :snippetBalanceMap (merge {} (:snippetBalanceMap snippet))
         :cursor (merge {} (:cursor snippet))))

(defn normalize-baked-clip [clip]
  (let [source (or (value->clj clip) {})]
    {:name (string-or (:name source) "")
     :duration (number-or (:duration source) 0)
     :channels (vec (or (:channels source) []))}))

(defn baked-ui-state [clip state]
  (let [clip (normalize-baked-clip clip)
        state (or state {})
        current-time (number-or (:currentTime state) (number-or (:time state) 0))
        playback-rate (number-or (:playbackRate state) (number-or (:speed state) 1))
        intensity-scale (number-or (:intensityScale state) (number-or (:weight state) 1))
        loop-mode (if (#{"repeat" "once" "pingpong"} (:loopMode state))
                    (:loopMode state)
                    (if (:loop state) "repeat" "once"))
        blend-mode (if (= (:blendMode state) "additive") "additive" "replace")]
    {:name (:name clip)
     :source "baked"
     :time current-time
     :currentTime current-time
     :duration (number-or (:duration state) (:duration clip))
     :speed playback-rate
     :playbackRate playback-rate
     :weight (number-or (:weight state) intensity-scale)
     :intensityScale intensity-scale
     :isPlaying (boolean (:isPlaying state))
     :isPaused (boolean (:isPaused state))
     :loop (if (contains? state :loop) (boolean (:loop state)) (not= loop-mode "once"))
     :loopMode loop-mode
     :reverse (boolean (:reverse state))
     :repeatCount (:repeatCount state)
     :requestedBlendMode (if (= (:requestedBlendMode state) "additive") "additive" blend-mode)
     :blendMode blend-mode
     :balance (max -1 (min 1 (number-or (:balance state) 0)))
     :category "baked"
     :easing (string-or (:easing state) "linear")
     :channels (vec (or (:channels state) (:channels clip) []))}))

(defn create-event-system []
  (let [event-listeners (atom #{})
        snippet-list-listeners (atom #{})
        global-state-listeners (atom #{})
        baked-clip-listeners (atom #{})
        playing-baked-listeners (atom #{})
        baked-animation-listeners (atom {})
        snippet-accessor (atom (fn [] []))
        global-state (atom "stopped")
        baked-clips (atom [])
        baked-states (atom {})]
    (letfn [(raw-snippets [] (@snippet-accessor))
            (raw-snippet [name] (first (filter #(= (:name %) name) (raw-snippets))))
            (ui-snippets [] (mapv snippet-ui-state (raw-snippets)))
            (emit-snippet-list! [] (emit-to! snippet-list-listeners (mapv :name (ui-snippets))))
            (get-baked-state [clip-name] (get @baked-states clip-name))
            (emit-baked-state! [clip-name]
              (when-let [listeners (get @baked-animation-listeners clip-name)]
                (emit-to! (atom listeners) (get-baked-state clip-name))))
            (playing-baked []
              (->> (vals @baked-states)
                   (filter #(or (:isPlaying %) (:isPaused %)))
                   vec))
            (emit-playing-baked! [] (emit-to! playing-baked-listeners (playing-baked)))
            (merge-baked! [clip-name patch]
              (let [clip (or (first (filter #(= (:name %) clip-name) @baked-clips))
                             {:name clip-name :duration 0 :channels []})
                    current (get-baked-state clip-name)
                    next-state (baked-ui-state clip (merge current (or patch {})))]
                (swap! baked-states assoc clip-name next-state)
                next-state))
            (emit-baked-param-change! [clip-name params]
              (emit-event! event-listeners
                           {:type "BAKED_ANIMATION_PARAMS_CHANGED"
                            :clipName clip-name
                            :params params})
              (emit-baked-state! clip-name)
              (emit-playing-baked!))]
      {:events (create-port event-listeners)
       :snippet-list-port (create-port snippet-list-listeners)
       :global-state-port (create-port global-state-listeners)
       :baked-clip-port (create-port baked-clip-listeners)
       :playing-baked-port (create-port playing-baked-listeners)
       :baked-animation-state-port
       (fn [clip-name]
         #js {:subscribe
              (fn [listener]
                (swap! baked-animation-listeners update clip-name (fnil conj #{}) listener)
                (subscription
                 (fn []
                   (swap! baked-animation-listeners update clip-name disj listener))))})
       :set-baked-state! (fn [clip-name state]
                           (let [next-state (baked-ui-state
                                             (or (first (filter #(= (:name %) clip-name) @baked-clips))
                                                 {:name clip-name :duration 0 :channels []})
                                             (value->clj state))]
                             (swap! baked-states assoc clip-name next-state)
                             (emit-baked-state! clip-name)
                             (emit-playing-baked!)
                             next-state))
       :get-baked-state get-baked-state
       :emitter
       #js {:events (create-port event-listeners)
            :setSnippetAccessor (fn [get-snippets]
                                  (reset! snippet-accessor
                                          (fn []
                                            (mapv normalize-snippet (or (get-snippets) [])))))
            :getSnippets (fn [] (clj->js (ui-snippets)))
            :getRawSnippets (fn [] (clj->js (mapv clone-snippet (raw-snippets))))
            :getRawSnippet (fn [name] (when-let [snippet (raw-snippet name)] (clj->js (clone-snippet snippet))))
            :getSnippet (fn [name] (when-let [snippet (raw-snippet name)] (clj->js (snippet-ui-state snippet))))
            :getGlobalState (fn [] @global-state)
            :emitSnippetAdded (fn [snippet-name]
                                (emit-event! event-listeners {:type "SNIPPET_ADDED" :snippetName snippet-name})
                                (emit-snippet-list!))
            :emitSnippetRemoved (fn [snippet-name]
                                  (emit-event! event-listeners {:type "SNIPPET_REMOVED" :snippetName snippet-name})
                                  (emit-snippet-list!))
            :emitPlayStateChanged (fn [snippet-name playing?]
                                    (emit-event! event-listeners
                                                 {:type "SNIPPET_PLAY_STATE_CHANGED"
                                                  :snippetName snippet-name
                                                  :isPlaying (boolean playing?)}))
            :emitSnippetUpdated (fn [snippet-name]
                                  (emit-event! event-listeners {:type "SNIPPET_UPDATED" :snippetName snippet-name}))
            :emitSnippetLooped (fn [data]
                                 (emit-event! event-listeners
                                              (merge {:type "SNIPPET_LOOPED"} (or (value->clj data) {}))))
            :emitSnippetCompleted (fn [snippet-name]
                                    (emit-event! event-listeners {:type "SNIPPET_COMPLETED" :snippetName snippet-name})
                                    (emit-event! event-listeners
                                                 {:type "SNIPPET_PLAY_STATE_CHANGED"
                                                  :snippetName snippet-name
                                                  :isPlaying false}))
            :emitKeyframeCompleted (fn [data]
                                     (emit-event! event-listeners
                                                  (merge {:type "KEYFRAME_COMPLETED"} (or (value->clj data) {}))))
            :emitGlobalPlaybackChanged (fn [state]
                                         (reset! global-state state)
                                         (emit-event! event-listeners
                                                      {:type "GLOBAL_PLAYBACK_CHANGED" :state state})
                                         (emit-to! global-state-listeners state))
            :emitSnippetSeeked (fn [snippet-name time]
                                 (emit-event! event-listeners
                                              {:type "SNIPPET_SEEKED"
                                               :snippetName snippet-name
                                               :time time}))
            :emitParamsChanged (fn [snippet-name params]
                                 (emit-event! event-listeners
                                              {:type "SNIPPET_PARAMS_CHANGED"
                                               :snippetName snippet-name
                                               :params (or (value->clj params) {})}))
            :emitBakedClipsLoaded (fn [clips]
                                    (let [next-clips (mapv normalize-baked-clip (or (value->clj clips) []))
                                          next-states (reduce
                                                       (fn [out clip]
                                                         (assoc out (:name clip)
                                                                (baked-ui-state clip (get @baked-states (:name clip)))))
                                                       {}
                                                       next-clips)]
                                      (reset! baked-clips next-clips)
                                      (reset! baked-states next-states)
                                      (emit-event! event-listeners
                                                   {:type "BAKED_CLIPS_LOADED" :clips next-clips})
                                      (emit-to! baked-clip-listeners next-clips)
                                      (emit-playing-baked!)
                                      (doseq [clip next-clips]
                                        (emit-baked-state! (:name clip)))))
            :emitBakedAnimationStarted (fn [clip-name state]
                                         (let [next-state (merge-baked! clip-name
                                                                        (assoc (or (value->clj state) {})
                                                                               :isPlaying true
                                                                               :isPaused false))]
                                           (emit-event! event-listeners
                                                        {:type "BAKED_ANIMATION_STARTED"
                                                         :clipName clip-name
                                                         :state next-state})
                                           (emit-baked-state! clip-name)
                                           (emit-playing-baked!)))
            :emitBakedAnimationStopped (fn [clip-name]
                                         (merge-baked! clip-name {:isPlaying false :isPaused false :time 0 :currentTime 0})
                                         (emit-event! event-listeners
                                                      {:type "BAKED_ANIMATION_STOPPED" :clipName clip-name})
                                         (emit-baked-state! clip-name)
                                         (emit-playing-baked!))
            :emitBakedAnimationPaused (fn [clip-name]
                                        (merge-baked! clip-name {:isPlaying false :isPaused true})
                                        (emit-event! event-listeners
                                                     {:type "BAKED_ANIMATION_PAUSED" :clipName clip-name})
                                        (emit-baked-state! clip-name)
                                        (emit-playing-baked!))
            :emitBakedAnimationResumed (fn [clip-name]
                                         (merge-baked! clip-name {:isPlaying true :isPaused false})
                                         (emit-event! event-listeners
                                                      {:type "BAKED_ANIMATION_RESUMED" :clipName clip-name})
                                         (emit-baked-state! clip-name)
                                         (emit-playing-baked!))
            :emitBakedAnimationCompleted (fn [clip-name]
                                           (let [current (merge-baked! clip-name {})
                                                 terminal-time (if (:reverse current) 0 (:duration current))]
                                             (merge-baked! clip-name {:isPlaying false
                                                                      :isPaused false
                                                                      :time terminal-time
                                                                      :currentTime terminal-time})
                                             (emit-event! event-listeners
                                                          {:type "BAKED_ANIMATION_COMPLETED" :clipName clip-name})
                                             (emit-baked-state! clip-name)
                                             (emit-playing-baked!)))
            :emitBakedAnimationProgress (fn [clip-name time duration]
                                          (merge-baked! clip-name {:time time :currentTime time :duration duration})
                                          (emit-event! event-listeners
                                                       {:type "BAKED_ANIMATION_PROGRESS"
                                                        :clipName clip-name
                                                        :time time
                                                        :duration duration})
                                          (emit-baked-state! clip-name))
            :emitBakedAnimationParamsChanged emit-baked-param-change!
            :getBakedClips (fn [] (clj->js @baked-clips))
            :getPlayingBakedAnimations (fn [] (clj->js (playing-baked)))
            :getBakedAnimationState (fn [clip-name] (when-let [state (get-baked-state clip-name)] (clj->js state)))
            :updateBakedAnimationState (fn [clip-name state]
                                         (merge-baked! clip-name (or (value->clj state) {}))
                                         (emit-baked-state! clip-name)
                                         (emit-playing-baked!))}})))

(defonce animation-event-system (create-event-system))

(def animationEventEmitter (:emitter animation-event-system))
(def snippetList$ (:snippet-list-port animation-event-system))
(def globalPlaybackState$ (:global-state-port animation-event-system))
(def bakedClipList$ (:baked-clip-port animation-event-system))
(def playingBakedAnimations$ (:playing-baked-port animation-event-system))
(def bakedAnimationProgress$ (:events animation-event-system))

(defn bakedAnimationState$ [clip-name]
  ((:baked-animation-state-port animation-event-system) clip-name))

(defn snippetState$ [snippet-name]
  #js {:subscribe
       (fn [listener]
         (let [subscription (js-call (aget animationEventEmitter "events")
                                     "subscribe"
                                     (fn [event]
                                       (let [event-map (value->clj event)
                                             type (:type event-map)]
                                         (when (or (#{"SNIPPET_ADDED" "SNIPPET_REMOVED"} type)
                                                   (= (:snippetName event-map) snippet-name))
                                           (listener (js-call animationEventEmitter "getSnippet" snippet-name))))))]
           subscription))})

(defn snippetTime$ [snippet-name]
  #js {:subscribe
       (fn [listener]
         (js-call (aget animationEventEmitter "events")
                  "subscribe"
                  (fn [event]
                    (let [event-map (value->clj event)]
                      (when (and (= (:type event-map) "SNIPPET_SEEKED")
                                 (= (:snippetName event-map) snippet-name))
                        (listener (:time event-map)))))))})

(defn upsert-snippet [snippets snippet playing?]
  (let [next-snippet (assoc snippet :isPlaying (boolean playing?))]
    (conj (vec (remove #(= (:name %) (:name next-snippet)) snippets)) next-snippet)))

(defn patch-snippet [snippets name patch]
  (mapv (fn [snippet]
          (if (= (:name snippet) name)
            (merge snippet patch)
            snippet))
        snippets))

(defn find-snippet [snippets name]
  (first (filter #(= (:name %) name) snippets)))

(defn agency-schedule! [agency snippet options]
  (js-call agency "scheduleSnippet" (clj->js snippet) (clj->js options)))

(defn agency-remove! [agency name]
  (js-call agency "removeSnippet" name))

(defn agency-update! [agency name params]
  (js-call agency "updateSnippet" name (clj->js params)))

(defn agency-seek! [agency name time-sec]
  (js-call agency "seekSnippet" name time-sec))

(defn playback-options [snippet options]
  (merge
   {:autoPlay true
    :sourceAgency (or (:sourceAgency options) "animationService")
    :priority (:snippetPriority snippet)}
   (or options {})))

(defn set-baked-state! [clip-name state]
  ((:set-baked-state! animation-event-system) clip-name state))

(defn get-baked-state [clip-name]
  ((:get-baked-state animation-event-system) clip-name))

(defn merge-baked-state! [clip-name patch]
  (set-baked-state! clip-name (merge (or (get-baked-state clip-name) {}) patch)))

(defn call-method [target method-name & args]
  (apply js-call target method-name args))

(defn baked-play-options [state]
  (clj->js {:loop (:loop state)
            :loopMode (:loopMode state)
            :repeatCount (:repeatCount state)
            :reverse (:reverse state)
            :playbackRate (:playbackRate state)
            :speed (:playbackRate state)
            :weight (:intensityScale state)
            :intensity (:intensityScale state)
            :blendMode (:requestedBlendMode state)
            :balance (:balance state)
            :easing (:easing state)}))

(defn createAnimationService [engine]
  (let [animation (animation-agency/create-animation-agency #js {:engine engine})
        snippets (atom [])
        baked-engine (atom engine)
        disposed? (atom false)
        playing? (atom false)
        transition-listeners (atom #{})]
    (js-call animationEventEmitter "setSnippetAccessor" (fn [] @snippets))
    (letfn [(notify-transition! []
              (let [snapshot #js {:context #js {:animations (clj->js (mapv clone-snippet @snippets))}}]
                (doseq [listener @transition-listeners]
                  (listener snapshot))))
            (load-snippet! [data playing-state options]
              (let [snippet (merge (normalize-snippet data)
                                   (select-keys options [:snippetPriority]))
                    name (:name snippet)]
                (swap! snippets upsert-snippet snippet playing-state)
                (js-call animationEventEmitter "emitSnippetAdded" name)
                (when playing-state
                  (agency-schedule! animation snippet (playback-options snippet options))
                  (js-call animationEventEmitter "emitPlayStateChanged" name true))
                (notify-transition!)
                name))
            (set-param! [name patch event-params restart?]
              (when-let [snippet (find-snippet @snippets name)]
                (let [next-snippet (merge snippet patch)]
                  (swap! snippets patch-snippet name patch)
                  (js-call animationEventEmitter "emitParamsChanged" name (clj->js event-params))
                  (agency-update! animation name event-params)
                  (when (and restart? (:isPlaying next-snippet))
                    (agency-remove! animation name)
                    (agency-schedule! animation next-snippet (playback-options next-snippet {})))
                  (notify-transition!))))
            (set-playing! [name next-playing]
              (when-let [snippet (find-snippet @snippets name)]
                (swap! snippets patch-snippet name {:isPlaying (boolean next-playing)})
                (if next-playing
                  (agency-schedule! animation (assoc snippet :isPlaying true) (playback-options snippet {}))
                  (agency-remove! animation name))
                (js-call animationEventEmitter "emitPlayStateChanged" name (boolean next-playing))
                (notify-transition!)
                true))
            (play-all! []
              (reset! playing? true)
              (js-call animationEventEmitter "emitGlobalPlaybackChanged" "playing")
              (doseq [snippet @snippets]
                (when (:isPlaying snippet)
                  (agency-schedule! animation snippet (playback-options snippet {}))))
              (notify-transition!))
            (pause-all! []
              (reset! playing? false)
              (doseq [snippet @snippets]
                (when (:isPlaying snippet)
                  (agency-remove! animation (:name snippet))))
              (js-call animationEventEmitter "emitGlobalPlaybackChanged" "paused")
              (notify-transition!))
            (stop-all! []
              (reset! playing? false)
              (doseq [snippet @snippets]
                (agency-remove! animation (:name snippet)))
              (swap! snippets (fn [entries] (mapv #(assoc % :isPlaying false :currentTime 0) entries)))
              (js-call animationEventEmitter "emitGlobalPlaybackChanged" "stopped")
              (notify-transition!))
            (set-baked-engine! [next-engine]
              (reset! baked-engine next-engine)
              (let [clips (mapv normalize-baked-clip (or (call-method next-engine "getAnimationClips") []))]
                (js-call animationEventEmitter "emitBakedClipsLoaded" (clj->js clips))))
            (current-baked-state [clip-name]
              (or (get-baked-state clip-name)
                  (baked-ui-state {:name clip-name :duration 0 :channels []} nil)))
            (play-baked! [clip-name options]
              (let [base (current-baked-state clip-name)
                    option-map (or (value->clj options) {})
                    next-state (merge base
                                      {:isPlaying true
                                       :isPaused false}
                                      (select-keys option-map
                                                   [:loop :loopMode :repeatCount :reverse
                                                    :blendMode :requestedBlendMode :balance :easing])
                                      (when (or (:playbackRate option-map) (:speed option-map))
                                        {:playbackRate (number-or (:playbackRate option-map) (:speed option-map))
                                         :speed (number-or (:playbackRate option-map) (:speed option-map))})
                                      (when (or (:weight option-map) (:intensity option-map))
                                        {:intensityScale (number-or (:weight option-map) (:intensity option-map))
                                         :weight (number-or (:weight option-map) (:intensity option-map))}))
                    handle (call-method @baked-engine "playAnimation" clip-name (baked-play-options next-state))]
                (js-call animationEventEmitter "emitBakedAnimationStarted" clip-name (clj->js next-state))
                handle))]
      (set-baked-engine! engine)
      (let [api #js {:loadFromJSON
           (fn [data] (load-snippet! data false {}))
           :updateSnippet
           (fn [data]
             (let [snippet (normalize-snippet data)
                   name (:name snippet)
                   existing (find-snippet @snippets name)
                   next-playing (boolean (:isPlaying (or existing snippet)))]
               (swap! snippets upsert-snippet snippet next-playing)
               (js-call animationEventEmitter "emitSnippetUpdated" name)
               (when next-playing
                 (agency-remove! animation name)
                 (agency-schedule! animation (assoc snippet :isPlaying true) (playback-options snippet {})))
               (notify-transition!)
               name))
           :schedule
           (fn
             ([data] (load-snippet! data true {}))
             ([data opts]
              (let [options (or (value->clj opts) {})
                    priority (number-or (:priority options) (:snippetPriority (or (value->clj data) {})))
                    data-with-priority (merge (or (value->clj data) {}) {:snippetPriority priority})]
                (load-snippet! data-with-priority true options))))
           :remove
           (fn [name]
             (agency-remove! animation name)
             (swap! snippets #(vec (remove (fn [snippet] (= (:name snippet) name)) %)))
             (js-call animationEventEmitter "emitSnippetRemoved" name)
             (notify-transition!))
           :play play-all!
           :pause pause-all!
           :stop stop-all!
           :enable (fn [name on?] (set-playing! name (not (false? on?))))
           :seek (fn [name offset-sec]
                   (let [time (max 0 (number-or offset-sec 0))]
                     (swap! snippets patch-snippet name {:currentTime time})
                     (agency-seek! animation name time)
                     (js-call animationEventEmitter "emitSnippetSeeked" name time)
                     (notify-transition!)
                     true))
           :getState (fn [] #js {:context #js {:animations (clj->js (mapv clone-snippet @snippets))}})
           :getScheduleSnapshot
           (fn []
             (clj->js
              (mapv (fn [snippet]
                      {:name (:name snippet)
                       :enabled (:isPlaying snippet)
                       :startsAt 0
                       :offset 0
                       :localTime (:currentTime snippet)
                       :duration (:duration snippet)
                       :loop (:loop snippet)
                       :priority (:snippetPriority snippet)
                       :playbackRate (:snippetPlaybackRate snippet)
                       :intensityScale (:snippetIntensityScale snippet)})
                    @snippets)))
           :getCurrentValue (fn [au-id] (number-or (call-method engine "getAU" au-id) 0))
           :isPlaying (fn [] @playing?)
           :loadFromLocal
           (fn
             ([key] nil)
             ([key cat] nil)
             ([key cat prio] nil))
           :setSnippetPlaybackRate (fn [name rate]
                                      (let [next-rate (max 0.0001 (number-or rate 1))]
                                        (set-param! name
                                                    {:snippetPlaybackRate next-rate}
                                                    {:rate next-rate :playbackRate next-rate}
                                                    false)))
           :setSnippetIntensityScale (fn [name scale]
                                        (let [next-scale (max 0 (number-or scale 1))]
                                          (set-param! name
                                                      {:snippetIntensityScale next-scale}
                                                      {:weight next-scale :intensityScale next-scale}
                                                      false)))
           :setSnippetBlendMode (fn [name mode]
                                   (let [next-mode (if (= mode "additive") "additive" "replace")]
                                     (set-param! name
                                                 {:snippetBlendMode next-mode}
                                                 {:blendMode next-mode}
                                                 false)))
           :setSnippetBalance (fn [name balance]
                                 (let [next-balance (max -1 (min 1 (number-or balance 0)))]
                                   (set-param! name
                                               {:snippetBalance next-balance}
                                               {:balance next-balance}
                                               true)))
           :setSnippetEasing (fn [name easing]
                                (set-param! name {:snippetEasing easing} {:easing easing} false))
           :setSnippetPriority (fn [name priority]
                                  (swap! snippets patch-snippet name {:snippetPriority (number-or priority 0)})
                                  (notify-transition!))
           :setSnippetLoopMode (fn [name mode]
                                  (let [next-mode (if (#{"repeat" "once" "pingpong"} mode) mode "once")]
                                    (set-param! name
                                                {:mixerLoopMode next-mode :loop (not= next-mode "once")}
                                                {:loopMode next-mode :mixerLoopMode next-mode :loop (not= next-mode "once")}
                                                false)))
           :setSnippetRepeatCount (fn [name repeat-count]
                                     (let [next-repeat (when (and (finite-number? repeat-count) (>= repeat-count 0))
                                                         (js/Math.floor repeat-count))]
                                       (set-param! name
                                                   {:mixerRepeatCount next-repeat}
                                                   {:repeatCount next-repeat}
                                                   false)))
           :setSnippetReverse (fn [name reverse?]
                                 (set-param! name
                                             {:mixerReverse (boolean reverse?)}
                                             {:reverse (boolean reverse?)}
                                             false))
           :setSnippetPlaying set-playing!
           :setSnippetTime (fn [name time-sec]
                             (let [time (max 0 (number-or time-sec 0))]
                               (swap! snippets patch-snippet name {:currentTime time})
                               (agency-seek! animation name time)
                               (js-call animationEventEmitter "emitSnippetSeeked" name time)
                               (notify-transition!)))
           :setSnippetLoopState (fn [name iteration local-time]
                                  (swap! snippets patch-snippet name
                                         (cond-> {:loopIteration (max 0 (number-or iteration 0))}
                                           (finite-number? local-time) (assoc :lastLoopTime local-time)))
                                  (notify-transition!))
           :pauseSnippet (fn [name] (set-playing! name false))
           :resumeSnippet (fn [name] (set-playing! name true))
           :restartSnippet (fn [name]
                              (when-let [snippet (find-snippet @snippets name)]
                                (agency-remove! animation name)
                                (agency-schedule! animation (assoc snippet :isPlaying true :currentTime 0)
                                                  (playback-options snippet {}))
                                (swap! snippets patch-snippet name {:isPlaying true :currentTime 0})
                                (js-call animationEventEmitter "emitPlayStateChanged" name true)
                                true))
           :stopSnippet (fn [name] (set-playing! name false))
           :onTransition (fn [listener]
                           (swap! transition-listeners conj listener)
                           (fn [] (swap! transition-listeners disj listener)))
           :dispose (fn []
                      (when-not @disposed?
                        (reset! disposed? true)
                        (js-call animation "dispose")
                        (reset! snippets [])
                        (js-call animationEventEmitter "setSnippetAccessor" (fn [] []))
                        (js-call animationEventEmitter "emitBakedClipsLoaded" #js [])))
           :debug (fn [] (.log js/console "[Polymer AnimationService]" (clj->js @snippets)))
           :setBakedAnimationEngine set-baked-engine!
           :playBakedAnimation
           (fn
             ([clip-name] (play-baked! clip-name nil))
             ([clip-name options] (play-baked! clip-name options)))
           :stopBakedAnimation (fn [clip-name]
                                 (call-method @baked-engine "stopAnimation" clip-name)
                                 (js-call animationEventEmitter "emitBakedAnimationStopped" clip-name))
           :pauseBakedAnimation (fn [clip-name]
                                  (call-method @baked-engine "pauseAnimation" clip-name)
                                  (js-call animationEventEmitter "emitBakedAnimationPaused" clip-name))
           :resumeBakedAnimation (fn [clip-name]
                                   (call-method @baked-engine "resumeAnimation" clip-name)
                                   (js-call animationEventEmitter "emitBakedAnimationResumed" clip-name))
           :setBakedAnimationSpeed (fn [clip-name speed]
                                     (let [next-speed (max 0.1 (number-or speed 1))]
                                       (call-method @baked-engine "setAnimationSpeed" clip-name next-speed)
                                       (merge-baked-state! clip-name {:speed next-speed :playbackRate next-speed})
                                       (js-call animationEventEmitter "emitBakedAnimationParamsChanged" clip-name
                                                                         (clj->js {:speed next-speed
                                                                                   :playbackRate next-speed}))))
           :setBakedAnimationWeight (fn [clip-name weight]
                                      (let [next-weight (max 0 (number-or weight 1))]
                                        (call-method @baked-engine "setAnimationIntensity" clip-name next-weight)
                                        (merge-baked-state! clip-name {:weight next-weight :intensityScale next-weight})
                                        (js-call animationEventEmitter "emitBakedAnimationParamsChanged" clip-name
                                                                          (clj->js {:weight next-weight
                                                                                    :intensityScale next-weight}))))
           :setBakedAnimationLoop (fn [clip-name loop?]
                                    (let [mode (if loop? "repeat" "once")]
                                      (call-method @baked-engine "setAnimationLoopMode" clip-name mode)
                                      (merge-baked-state! clip-name {:loop (boolean loop?) :loopMode mode})
                                      (js-call animationEventEmitter "emitBakedAnimationParamsChanged" clip-name
                                                                        (clj->js {:loop (boolean loop?)
                                                                                  :loopMode mode}))))
           :setBakedAnimationLoopMode (fn [clip-name mode]
                                        (let [next-mode (if (#{"repeat" "once" "pingpong"} mode) mode "once")]
                                          (call-method @baked-engine "setAnimationLoopMode" clip-name next-mode)
                                          (merge-baked-state! clip-name {:loop (not= next-mode "once") :loopMode next-mode})
                                          (js-call animationEventEmitter "emitBakedAnimationParamsChanged" clip-name
                                                                            (clj->js {:loop (not= next-mode "once")
                                                                                      :loopMode next-mode}))))
           :setBakedAnimationRepeatCount (fn [clip-name repeat-count]
                                           (let [next-repeat (when (and (finite-number? repeat-count) (>= repeat-count 0))
                                                               (js/Math.floor repeat-count))]
                                             (call-method @baked-engine "setAnimationRepeatCount" clip-name next-repeat)
                                             (merge-baked-state! clip-name {:repeatCount next-repeat})
                                             (js-call animationEventEmitter "emitBakedAnimationParamsChanged" clip-name
                                                                               (clj->js {:repeatCount next-repeat}))))
           :setBakedAnimationReverse (fn [clip-name reverse?]
                                       (call-method @baked-engine "setAnimationReverse" clip-name (boolean reverse?))
                                       (merge-baked-state! clip-name {:reverse (boolean reverse?)})
                                       (js-call animationEventEmitter "emitBakedAnimationParamsChanged" clip-name
                                                                         (clj->js {:reverse (boolean reverse?)})))
           :setBakedAnimationBlendMode (fn [clip-name mode]
                                         (let [next-mode (if (= mode "additive") "additive" "replace")]
                                           (call-method @baked-engine "setAnimationBlendMode" clip-name next-mode)
                                           (merge-baked-state! clip-name {:blendMode next-mode :requestedBlendMode next-mode})
                                           (js-call animationEventEmitter "emitBakedAnimationParamsChanged" clip-name
                                                                             (clj->js {:blendMode next-mode}))))
           :setBakedAnimationBalance (fn [clip-name balance]
                                       (let [next-balance (max -1 (min 1 (number-or balance 0)))]
                                         (merge-baked-state! clip-name {:balance next-balance})
                                         (js-call animationEventEmitter "emitBakedAnimationParamsChanged" clip-name
                                                                           (clj->js {:balance next-balance}))))
           :setBakedAnimationEasing (fn [clip-name easing]
                                      (merge-baked-state! clip-name {:easing easing})
                                      (js-call animationEventEmitter "emitBakedAnimationParamsChanged" clip-name
                                                                        (clj->js {:easing easing})))
           :seekBakedAnimation (fn [clip-name time-sec]
                                 (let [time (max 0 (number-or time-sec 0))
                                       duration (:duration (current-baked-state clip-name))]
                                   (or (call-method @baked-engine "seekAnimation" clip-name time)
                                       (call-method @baked-engine "setAnimationTime" clip-name time)
                                       (call-method @baked-engine "setTime" clip-name time))
                                   (merge-baked-state! clip-name {:time time :currentTime time})
                                   (js-call animationEventEmitter "emitBakedAnimationProgress" clip-name time duration)
                                   true))
           :canSeekBakedAnimation (fn []
                                    (boolean (or (aget @baked-engine "seekAnimation")
                                                 (aget @baked-engine "setAnimationTime")
                                                 (aget @baked-engine "setTime"))))
           :stopAllBakedAnimations (fn []
                                     (let [active (js->clj (js-call animationEventEmitter "getPlayingBakedAnimations")
                                                           :keywordize-keys true)]
                                       (call-method @baked-engine "stopAllAnimations")
                                       (doseq [animation active]
                                         (js-call animationEventEmitter "emitBakedAnimationStopped" (:name animation)))))
           :getBakedClips (fn [] (js-call animationEventEmitter "getBakedClips"))
           :getPlayingBakedAnimations (fn [] (js-call animationEventEmitter "getPlayingBakedAnimations"))}]
        (js/Object.defineProperty api "playing" #js {:get (fn [] @playing?)})
        api))))

(defn get-storage [storage]
  (or storage
      (try
        (aget js/globalThis "localStorage")
        (catch :default _ nil))))

(defn parse-json [raw fallback]
  (if (and raw (string? raw))
    (try
      (js->clj (.parse js/JSON raw) :keywordize-keys true)
      (catch :default _ fallback))
    fallback))

(defn parse-string-list [raw]
  (let [parsed (parse-json raw [])]
    (if (sequential? parsed)
      (vec (filter string? parsed))
      [])))

(defn getBundledSnippetNames [list-key]
  ;; Polymer no longer ships Latticework's TS snippet glob catalog. User-authored
  ;; snippets remain available through localStorage; built-ins can be ported as
  ;; Polymer data in a separate focused PR.
  #js [])

(defn getStoredSnippetNames
  ([list-key] (getStoredSnippetNames list-key nil))
  ([list-key storage]
   (if-not (snippet-categories list-key)
     #js []
     (let [resolved-storage (get-storage storage)]
       (if-not resolved-storage
         #js []
         (clj->js (parse-string-list (.getItem resolved-storage list-key))))))))

(defn getAvailableSnippetNames
  ([list-key] (getAvailableSnippetNames list-key nil))
  ([list-key storage]
   (getStoredSnippetNames list-key storage)))

(defn resolveSnippetEntry
  ([list-key name] (resolveSnippetEntry list-key name nil))
  ([list-key name storage]
   (js/Promise.
    (fn [resolve _reject]
      (let [resolved-storage (get-storage storage)
            storage-key (str list-key "/" name)
            raw (when resolved-storage (.getItem resolved-storage storage-key))
            parsed (parse-json raw nil)]
        (resolve
         (when (map? parsed)
           (clj->js {:name (string-or (:name parsed) name)
                     :data parsed
                     :source "localStorage"
                     :storageKey storage-key}))))))))

(defn preloadAllSnippets [] nil)

(defn clearPreloadedSnippets
  ([] (clearPreloadedSnippets nil))
  ([storage]
   (let [resolved-storage (get-storage storage)
         manifest (when resolved-storage
                    (parse-json (.getItem resolved-storage bundled-snippet-manifest-key) {}))]
     (when resolved-storage
       (doseq [[list-key names] manifest
               name names]
         (.removeItem resolved-storage (str (key->string list-key) "/" name)))
       (.removeItem resolved-storage bundled-snippet-manifest-key)
       (.removeItem resolved-storage bundled-snippet-version-key)))))
