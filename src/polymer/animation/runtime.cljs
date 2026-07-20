(ns polymer.animation.runtime
  (:require [polymer.animation.domain :as domain]))

;; Runtime functions are Animation's side-effect boundary. Everything here is
;; allowed to touch JavaScript handles or an injected animation engine; planner,
;; scheduler decision logic, and domain transforms are not.

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
  ;; Some callers still pass a larger engine object. Animation adapts it once
  ;; into the smaller runtime API it needs, keeping that dependency at this
  ;; boundary instead of leaking engine calls into other agencies.
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
                                           (call-js engine "seek" clip-name offset-sec)
                                           ;; Embody's facade exposes seekAnimation and
                                           ;; returns undefined; report success explicitly
                                           ;; so drift correction is not treated as failed.
                                           (when (js-method engine "seekAnimation")
                                             (call-js engine "seekAnimation" clip-name offset-sec)
                                             true)))
                     :cleanupSnippet (fn [clip-name]
                                       (or (call-js engine "cleanupSnippet" clip-name)
                                           (call-js engine "stopAnimation" clip-name)))
                     :getAnimationState (fn [clip-name]
                                          (call-js engine "getAnimationState" clip-name))}]
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
  (let [config (or config #js {})
        runtime (aget config "runtime")
        engine (aget config "engine")]
    (cond
      runtime runtime
      engine (engine->runtime engine)
      :else nil)))

(defn typed-snippet-js [name channels]
  (clj->js {:name name :channels channels}))

(defn play-typed-snippet! [runtime snippet options]
  (let [name (:name snippet)
        channels (domain/typed-channels snippet)
        channels-js (clj->js channels)
        clip-options (domain/snippet->clip-options snippet options)
        snippet-js (typed-snippet-js name channels)]
    (if (false? (:autoPlay options))
      (or (call-js runtime "buildTypedClip" name channels-js clip-options)
          (call-js runtime "playTypedSnippet" snippet-js clip-options))
      (or (call-js runtime "playTypedSnippet" snippet-js clip-options)
          (play-built-handle!
           (call-js runtime "buildTypedClip" name channels-js clip-options))))))

(defn play-legacy-snippet! [runtime snippet options]
  (when-let [curves (:curves snippet)]
    (let [name (:name snippet)
          curves-js (clj->js curves)
          clip-options (domain/snippet->legacy-clip-options snippet options)]
      (if (false? (:autoPlay options))
        (or (call-js runtime "buildClip" name curves-js clip-options)
            (call-js runtime "playSnippet" name curves-js clip-options))
        (call-js runtime "playSnippet" name curves-js clip-options)))))

(defn play-snippet! [runtime snippet options]
  ;; Typed snippets are Polymer's canonical animation contract. Legacy curve
  ;; playback is only a compatibility path for runtimes that have not adopted
  ;; typed channels yet.
  (if (domain/typed-channels snippet)
    (if (or (js-method runtime "playTypedSnippet")
            (js-method runtime "buildTypedClip"))
      (play-typed-snippet! runtime snippet options)
      (when (seq (:curves snippet))
        (play-legacy-snippet! runtime snippet options)))
    (play-legacy-snippet! runtime snippet options)))

(defn cleanup-snippet! [runtime handle name]
  (when-let [stop (js-method handle "stop")]
    (.call stop handle))
  (when runtime
    (call-js runtime "cleanupSnippet" name)))

(defn seek-snippet! [runtime handle name offset-sec]
  (let [normalized-offset (max 0 (or offset-sec 0))]
    (or (when handle
          (or (call-js handle "setTime" normalized-offset)
              (call-js handle "seek" normalized-offset)
              ;; Embody clip handles expose seekTo and return undefined.
              (when (js-method handle "seekTo")
                (call-js handle "seekTo" normalized-offset)
                true)))
        (when runtime
          (or (call-js runtime "setSnippetTime" name normalized-offset)
              (call-js runtime "seekSnippet" name normalized-offset)
              (call-js runtime "seek" name normalized-offset)
              (call-js runtime "updateClipParams"
                       name
                       (clj->js {:time normalized-offset
                                 :offsetSec normalized-offset})))))))

(defn update-snippet! [runtime handle name params]
  (let [params-js (clj->js params)]
    (or (when handle
          (or (call-js handle "updateParams" params-js)
              (call-js handle "setParams" params-js)))
        (when runtime
          (call-js runtime "updateClipParams" name params-js)))))
