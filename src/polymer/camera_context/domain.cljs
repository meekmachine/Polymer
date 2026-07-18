(ns polymer.camera-context.domain)

;; Camera Context intentionally works with plain data, not Three.js objects.
;; The host/runtime boundary can read camera, controls, and model handles, then
;; pass stable vector/quaternion facts into Polymer. Everything below is pure
;; math over those facts so Gaze, Eye/Head, and future agencies can consume the
;; same camera-relative context without inheriting a rendering-engine import.

(def default-epsilon 1.0e-4)
(def default-yaw-weight 0.35)
(def default-pitch-weight 0.2)

(def zero-vector3 {:x 0 :y 0 :z 0})
(def identity-quaternion {:x 0 :y 0 :z 0 :w 1})
(def zero-offset {:x 0 :y 0})

(defn finite-number?
  [value]
  (and (number? value) (js/isFinite value)))

(defn number-or
  [value fallback]
  (if (finite-number? value) value fallback))

(defn clamp
  [lo hi value]
  (min hi (max lo value)))

(defn data-map
  [value]
  (cond
    (map? value) value
    value (js->clj value :keywordize-keys true)
    :else {}))

(defn normalize-vector3
  ([value] (normalize-vector3 value zero-vector3))
  ([value fallback]
   (let [input (data-map value)
         fallback (merge zero-vector3 fallback)]
     {:x (number-or (:x input) (:x fallback))
      :y (number-or (:y input) (:y fallback))
      :z (number-or (:z input) (:z fallback))})))

(defn normalize-quaternion
  [value]
  (let [input (data-map value)
        raw {:x (number-or (:x input) 0)
             :y (number-or (:y input) 0)
             :z (number-or (:z input) 0)
             :w (number-or (:w input) 1)}
        length (js/Math.hypot (:x raw) (:y raw) (:z raw) (:w raw))]
    (if (< length default-epsilon)
      identity-quaternion
      {:x (/ (:x raw) length)
       :y (/ (:y raw) length)
       :z (/ (:z raw) length)
       :w (/ (:w raw) length)})))

(defn subtract-vector3
  [a b]
  {:x (- (:x a) (:x b))
   :y (- (:y a) (:y b))
   :z (- (:z a) (:z b))})

(defn length-sq
  [v]
  (+ (* (:x v) (:x v))
     (* (:y v) (:y v))
     (* (:z v) (:z v))))

(defn normalize-direction
  [v epsilon]
  (let [length (js/Math.sqrt (length-sq v))]
    (if (< length epsilon)
      zero-vector3
      {:x (/ (:x v) length)
       :y (/ (:y v) length)
       :z (/ (:z v) length)})))

(defn invert-unit-quaternion
  [q]
  {:x (- (:x q))
   :y (- (:y q))
   :z (- (:z q))
   :w (:w q)})

(defn apply-quaternion
  [v q]
  ;; This mirrors Three.js Vector3.applyQuaternion, but stays independent from
  ;; Three so the agency can run in workers and tests with only CLJS data.
  (let [x (:x v)
        y (:y v)
        z (:z v)
        qx (:x q)
        qy (:y q)
        qz (:z q)
        qw (:w q)
        ix (+ (* qw x) (* qy z) (- (* qz y)))
        iy (+ (* qw y) (* qz x) (- (* qx z)))
        iz (+ (* qw z) (* qx y) (- (* qy x)))
        iw (- 0 (* qx x) (* qy y) (* qz z))]
    {:x (+ (* ix qw) (* iw (- qx)) (* iy (- qz)) (- (* iz (- qy))))
     :y (+ (* iy qw) (* iw (- qy)) (* iz (- qx)) (- (* ix (- qz))))
     :z (+ (* iz qw) (* iw (- qz)) (* ix (- qy)) (- (* iy (- qx))))}))

(defn compute-camera-relative-offset
  "Return a normalized gaze offset from camera position relative to a target.

  Positive x means the camera is toward the model's local right side. Positive y
  means the camera is above the target. The weights match the Latticework helper
  defaults, while the inputs are portable data facts instead of Three objects."
  ([camera-position target-position model-quaternion]
   (compute-camera-relative-offset camera-position target-position model-quaternion {}))
  ([camera-position target-position model-quaternion options]
   (let [options (data-map options)
         epsilon (number-or (:epsilon options) default-epsilon)
         yaw-weight (number-or (:yawWeight options)
                               (number-or (:yaw-weight options) default-yaw-weight))
         pitch-weight (number-or (:pitchWeight options)
                                 (number-or (:pitch-weight options) default-pitch-weight))
         camera-position (normalize-vector3 camera-position)
         target-position (normalize-vector3 target-position)
         world-offset (subtract-vector3 camera-position target-position)]
     (if (< (length-sq world-offset) epsilon)
       zero-offset
       (let [world-direction (normalize-direction world-offset epsilon)
             local-direction (normalize-direction
                              (apply-quaternion world-direction
                                                (invert-unit-quaternion
                                                 (normalize-quaternion model-quaternion)))
                              epsilon)
             yaw-angle (js/Math.atan2 (:x local-direction) (:z local-direction))
             pitch-angle (js/Math.atan2
                          (:y local-direction)
                          (max (js/Math.hypot (:x local-direction) (:z local-direction))
                               epsilon))
             half-pi (/ (.-PI js/Math) 2)
             third-pi (/ (.-PI js/Math) 3)]
         {:x (* (clamp -1 1 (/ yaw-angle half-pi)) yaw-weight)
          :y (* (clamp -1 1 (/ pitch-angle third-pi)) pitch-weight)})))))

(defn camera-payload
  [command]
  (let [payload (data-map command)
        facts (data-map (:facts payload))]
    (merge facts payload)))

(defn normalize-camera-fact
  "Create the stable fact payload published by the agency.

  The command can either put facts at the top level or under :facts. Keeping the
  shape forgiving lets tests, workers, and runtime adapters evolve without
  changing the internal fact contract."
  [command config observed-at sequence]
  (let [payload (camera-payload command)
        camera-position (normalize-vector3
                         (or (:cameraPosition payload)
                             (:camera-position payload)
                             (get-in payload [:camera :position])
                             (:position payload)))
        target-position (normalize-vector3
                         (or (:targetPosition payload)
                             (:target-position payload)
                             (get-in payload [:controls :target])
                             (:target payload)))
        model-quaternion (normalize-quaternion
                          (or (:modelQuaternion payload)
                              (:model-quaternion payload)
                              (get-in payload [:model :quaternion])
                              (get-in payload [:model :worldQuaternion])
                              (:worldQuaternion payload)))
        offset (compute-camera-relative-offset camera-position
                                               target-position
                                               model-quaternion
                                               config)]
    {:kind "camera.relative"
     :agency "cameraContext"
     :sequence sequence
     :source (or (:source payload) "runtime")
     :observedAt observed-at
     :cameraPosition camera-position
     :targetPosition target-position
     :modelQuaternion model-quaternion
     :relativeOffset offset
     :stale false}))
