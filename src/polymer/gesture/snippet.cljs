(ns polymer.gesture.snippet
  (:require [polymer.gesture.state :as state]))

;; Gesture snippet construction is pure. It turns LoomLarge-authored arm/hand
;; snapshots into typed Animation snippets; Animation owns the eventual Embody
;; runtime side effect.

(def eps 0.000001)
(def rotation-channels ["rx" "ry" "rz"])
(def position-channels ["tx" "ty" "tz"])

(defn abs-value [value]
  (js/Math.abs value))

(defn radians->degrees [value]
  (* value (/ 180 js/Math.PI)))

(defn quaternion->euler [[x y z w]]
  (let [sinr-cosp (* 2 (+ (* w x) (* y z)))
        cosr-cosp (- 1 (* 2 (+ (* x x) (* y y))))
        roll (js/Math.atan2 sinr-cosp cosr-cosp)
        sinp (* 2 (- (* w y) (* z x)))
        pitch (if (>= (abs-value sinp) 1)
                (* (if (neg? sinp) -1 1) (/ js/Math.PI 2))
                (js/Math.asin sinp))
        siny-cosp (* 2 (+ (* w z) (* x y)))
        cosy-cosp (- 1 (* 2 (+ (* y y) (* z z))))
        yaw (js/Math.atan2 siny-cosp cosy-cosp)]
    [roll pitch yaw]))

(defn finite-values [values expected-count]
  (let [items (vec values)]
    (when (and (= expected-count (count items))
               (every? state/finite-number? items))
      items)))

(defn transform-values [transform]
  (let [rotation (when-let [q (finite-values (:rotation transform) 4)]
                   (zipmap rotation-channels (quaternion->euler q)))
        position (when-let [p (finite-values (:position transform) 3)]
                   (zipmap position-channels p))]
    (merge rotation position)))

(defn channel-kind [channel]
  (if (#{"rx" "ry" "rz"} channel) :rotation :position))

(defn target-value->channel [bone-id channel value curve]
  (let [rotation? (= :rotation (channel-kind channel))
        metric (if rotation? (abs-value (radians->degrees value)) (abs-value value))
        target (cond->
                {:type "bone"
                 :id bone-id
                 :channel channel
                 :scale (if (neg? value) -1 1)}
                 rotation? (assoc :maxDegrees metric)
                 (not rotation?) (assoc :maxUnits metric))]
    {:target target
     :keyframes curve}))

(defn static-curve [duration-sec ramp-ratio hold-ratio return?]
  (let [ramp-end (min duration-sec (max 0.01 (* duration-sec ramp-ratio)))
        hold-end (min duration-sec (+ ramp-end (* duration-sec hold-ratio)))]
    (cond-> [{:time 0 :intensity 0}
             {:time ramp-end :intensity 1}
             {:time hold-end :intensity 1}]
      return? (conj {:time duration-sec :intensity 0})
      (not return?) (conj {:time duration-sec :intensity 1}))))

(defn static-bone-channels [gesture config]
  (let [duration-sec (/ (state/number-or (:durationMs gesture) (:defaultDurationMs config)) 1000)
        return? (if (contains? gesture :returnToBase)
                  (not (false? (:returnToBase gesture)))
                  (:returnToBase config))
        curve (static-curve duration-sec
                            (:rampRatio config)
                            (:holdRatio config)
                            return?)]
    (->> (:bones gesture)
         (mapcat (fn [[bone-id transform]]
                   (->> (transform-values transform)
                        (keep (fn [[channel value]]
                                (when (> (abs-value value) eps)
                                  (target-value->channel bone-id channel value curve)))))))
         vec)))

(defn keyframe-values [gesture]
  (->> (:keyframes gesture)
       (mapcat (fn [keyframe]
                 (let [time-sec (/ (state/number-or (:timeMs keyframe) 0) 1000)]
                   (mapcat
                    (fn [[bone-id transform]]
                      (map (fn [[channel value]]
                             {:boneId bone-id
                              :channel channel
                              :time time-sec
                              :value value})
                           (transform-values transform)))
                    (:bones keyframe)))))
       (remove #(<= (abs-value (:value %)) eps))
       vec))

(defn group-keyframe-values [values]
  (reduce (fn [acc value]
            (update acc [(:boneId value) (:channel value)] conj value))
          {}
          values))

(defn dynamic-channel [[bone-id channel] values duration-sec return?]
  (let [pivot (apply max-key #(abs-value (:value %)) values)
        pivot-value (:value pivot)
        max-value (abs-value pivot-value)
        rotation? (= :rotation (channel-kind channel))
        target (cond->
                {:type "bone"
                 :id bone-id
                 :channel channel
                 :scale (if (neg? pivot-value) -1 1)}
                 rotation? (assoc :maxDegrees (abs-value (radians->degrees pivot-value)))
                 (not rotation?) (assoc :maxUnits max-value))
        signed-scale (:scale target)
        curve (->> values
                   (map (fn [value]
                          {:time (:time value)
                           :intensity (/ (* signed-scale (:value value)) max-value)}))
                   (sort-by :time)
                   vec)
        with-start (if (zero? (:time (first curve)))
                     curve
                     (into [{:time 0 :intensity 0}] curve))
        with-end (if return?
                   (conj with-start {:time duration-sec :intensity 0})
                   with-start)]
    {:target target
     :keyframes with-end}))

(defn dynamic-bone-channels [gesture config]
  (let [duration-sec (/ (state/number-or (:durationMs gesture) (:defaultDurationMs config)) 1000)
        return? (if (contains? gesture :returnToBase)
                  (not (false? (:returnToBase gesture)))
                  (:returnToBase config))
        values (keyframe-values gesture)]
    (->> (group-keyframe-values values)
         (map (fn [[key values]]
                (dynamic-channel key values duration-sec return?)))
         vec)))

(defn gesture-name [gesture now]
  (str "polymer:gesture:" (:id gesture) ":" now))

(defn build-gesture-snippet [gesture config context]
  (let [now (state/now-ms)
        channels (if (seq (:keyframes gesture))
                   (dynamic-bone-channels gesture config)
                   (static-bone-channels gesture config))
        duration-ms (state/number-or (:durationMs gesture) (:defaultDurationMs config))
        duration-sec (/ duration-ms 1000)]
    (when (seq channels)
      {:name (or (:name context) (gesture-name gesture now))
       :channels channels
       :maxTime duration-sec
       :loop (boolean (:loop gesture))
       :snippetPriority (int (state/number-or (:priority gesture) (:priority config)))
       :snippetPlaybackRate 1
       :snippetIntensityScale (state/number-or (:intensity context) (:intensity config))
       :metadata {:agency "gesture"
                  :gestureId (:id gesture)
                  :gestureName (:name gesture)
                  :description (:description gesture)
                  :emoji (:emoji gesture)
                  :scope (:scope gesture)
                  :captureSource (:captureSource gesture)
                  :sourceText (:sourceText gesture)
                  :textRepresentation (:textRepresentation gesture)
                  :createdAt (:createdAt gesture)
                  :updatedAt (:updatedAt gesture)
                  :durationMs duration-ms
                  :tags (:tags gesture)
                  :affectedBones (:affectedBones gesture)
                  :affectedAUs (:affectedAUs gesture)}})))
