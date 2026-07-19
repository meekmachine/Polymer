(ns polymer.eye-head.snippet)

;; Eye/Head snippets use typed AU channels. Numeric curve keys remain for
;; compatibility with older animation builds, while typed channels are the
;; canonical contract for the current Animation agency.

(def eye-yaw-left 61)
(def eye-yaw-right 62)
(def eye-pitch-up 63)
(def eye-pitch-down 64)
(def head-yaw-left 51)
(def head-yaw-right 52)
(def head-pitch-up 53)
(def head-pitch-down 54)
(def head-roll-left 55)
(def head-roll-right 56)

(defn seconds
  [duration-ms]
  (/ (max 1 duration-ms) 1000))

(defn axis-curves
  [negative-au positive-au value duration-sec]
  (let [value (max -1 (min 1 value))
        negative-value (if (neg? value) (js/Math.abs value) 0)
        positive-value (if (pos? value) value 0)]
    {(str negative-au) [{:time 0 :intensity 0 :inherit true}
                        {:time duration-sec :intensity negative-value}]
     (str positive-au) [{:time 0 :intensity 0 :inherit true}
                        {:time duration-sec :intensity positive-value}]}))

(defn au-channel
  [au-id curve]
  {:target {:type "au" :id au-id}
   :keyframes curve})

(defn curves->channels
  [curves]
  (into []
        (map (fn [[au-id curve]]
               (au-channel (js/parseInt au-id 10) curve)))
        curves))

(defn merge-curves
  [& curve-maps]
  (apply merge curve-maps))

(defn build-eye-curves
  [request]
  (let [target (:target request)
        duration-sec (seconds (:eyeDurationMs request))
        yaw (* (:x target) (:eyeIntensity request))
        pitch (* (:y target) (:eyeIntensity request))]
    (merge-curves
     (axis-curves eye-yaw-left eye-yaw-right yaw duration-sec)
     (axis-curves eye-pitch-down eye-pitch-up pitch duration-sec))))

(defn build-head-curves
  [request]
  (let [target (:target request)
        duration-sec (seconds (:headDurationMs request))
        yaw (* (:x target) (:headIntensity request))
        pitch (* (:y target) (:headIntensity request))
        roll (* (:headRoll request) (:headIntensity request))]
    (merge-curves
     (axis-curves head-yaw-left head-yaw-right yaw duration-sec)
     (axis-curves head-pitch-down head-pitch-up pitch duration-sec)
     (axis-curves head-roll-left head-roll-right roll duration-sec))))

(defn build-gaze-snippet
  [request config]
  (let [eye-curves (when (:eyeEnabled request) (build-eye-curves request))
        head-curves (when (and (:headEnabled request) (:headFollowEyes request))
                      (build-head-curves request))
        curves (merge-curves (or eye-curves {}) (or head-curves {}))
        channels (curves->channels curves)
        max-time (max (seconds (:eyeDurationMs request))
                      (seconds (:headDurationMs request)))]
    {:name (str "eyeHeadTracking:gaze:" (:requestId request))
     :curves curves
     :channels channels
     :maxTime max-time
     :loop false
     :mixerClampWhenFinished true
     :snippetPriority (:snippetPriority config)
     :snippetPlaybackRate 1.0
     :snippetIntensityScale 1.0
     :metadata {:agency "eyeHeadTracking"
                :sourceAgency (:sourceAgency request)
                :requestId (:requestId request)
                :mode (:mode request)
                :target (:target request)}}))
