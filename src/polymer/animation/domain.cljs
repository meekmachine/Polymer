(ns polymer.animation.domain)

;; Domain functions describe Polymer animation data without touching a runtime.
;; They answer questions like "is this a typed viseme snippet?" and "which clip
;; options should a runtime receive?" so the scheduler can stay focused on
;; ordering and lifecycle.

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

(defn typed-jaw-channel? [channel]
  (let [target (typed-channel-target channel)]
    (or (and (= "lipSync" (:type target))
             (= 103 (:id target)))
        (and (= "bone" (:type target))
             (= "JAW" (:id target))))))

(defn typed-viseme-snippet? [snippet]
  (boolean (some typed-viseme-channel? (typed-channels snippet))))

(defn typed-jaw-snippet? [snippet]
  (boolean (some typed-jaw-channel? (typed-channels snippet))))

(defn typed-channel-summary [snippet]
  ;; The summary is diagnostic-only. A transducer is appropriate here because
  ;; this is a pure map/reduce over channel data with no ordering side effects.
  (into []
        (map (fn [channel]
               (let [keyframes (:keyframes channel)
                     peak (transduce (map :intensity) max 0 keyframes)]
                 {:target (:target channel)
                  :frames (count keyframes)
                  :firstSec (:time (first keyframes))
                  :lastSec (:time (last keyframes))
                  :peak peak})))
        (or (typed-channels snippet) [])))

(defn explicit-auto-viseme-jaw [snippet]
  (when (contains? snippet :autoVisemeJaw)
    (:autoVisemeJaw snippet)))

(defn snippet->clip-options-map
  ([snippet options] (snippet->clip-options-map snippet options false))
  ([snippet options legacy-fallback?]
   ;; Typed channels carry their own namespace, so the normal Embody path does
   ;; not need snippetCategory. The legacy curve fallback still needs
   ;; "visemeSnippet" because older runtimes otherwise treat numeric lip ids as
   ;; facial AU ids.
   (let [category (:snippetCategory snippet)
         typed-viseme? (typed-viseme-snippet? snippet)
         viseme-category? (or (viseme-snippet-category? category) typed-viseme?)
         curves (or (:curves snippet) {})
         ;; Curve maps may use string or keyword keys after host keywordize.
         has-jaw-curve? (or (contains? curves "103")
                            (contains? curves :103)
                            (typed-jaw-snippet? snippet))
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

(defn schedule-log-payload [snippet options source-agency]
  {:name (:name snippet)
   :sourceAgency source-agency
   :typed (boolean (typed-channels snippet))
   :channelCount (count (typed-channels snippet))
   :channels (typed-channel-summary snippet)
   :curveKeys (vec (keys (or (:curves snippet) {})))
   :options options})
