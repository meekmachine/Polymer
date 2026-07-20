(ns polymer.gaze.domain)

;; Gaze domain code is pure data transformation. It turns outside facts and
;; goals into normalized targets and scores, but it never schedules work or
;; talks to an animation/runtime API.

(def zero-target {:x 0 :y 0 :z 0})

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

(defn normalize-target
  ([value] (normalize-target value zero-target))
  ([value fallback]
   (let [input (data-map value)
         fallback (merge zero-target fallback)]
     {:x (clamp -1 1 (number-or (:x input) (:x fallback)))
      :y (clamp -1 1 (number-or (:y input) (:y fallback)))
      :z (number-or (:z input) (:z fallback))})))

(defn add-targets
  [base offset]
  {:x (clamp -1 1 (+ (:x base) (:x offset)))
   :y (clamp -1 1 (+ (:y base) (:y offset)))
   :z (number-or (:z base) 0)})

(defn target-distance
  [a b]
  (js/Math.hypot (- (:x a) (:x b))
                 (- (:y a) (:y b))))

(defn mirror-target
  [target mirrored?]
  (if mirrored?
    (update target :x -)
    target))

(defn smooth-alpha
  [distance smooth-factor]
  ;; This follows the Latticework experimental behavior: smoothFactor 1 means
  ;; immediate target adoption, while lower values ease toward distant targets
  ;; without letting a single update jump too far.
  (let [base (max 0 smooth-factor)]
    (if (>= base 1)
      1
      (min 0.7 (+ base (* distance 0.25))))))

(defn smooth-target
  [previous raw-target smooth-factor]
  (let [distance (target-distance raw-target previous)
        alpha (smooth-alpha distance smooth-factor)]
    {:x (+ (:x previous) (* (- (:x raw-target) (:x previous)) alpha))
     :y (+ (:y previous) (* (- (:y raw-target) (:y previous)) alpha))
     :z (:z raw-target)}))

(defn movement-duration-ms
  [base-ms delta scale minimum-ms]
  (int (js/Math.round (max minimum-ms (* base-ms (+ scale delta))))))

(defn plan-target
  [target previous-target config options now-ms]
  (let [raw-target (mirror-target (normalize-target target) (:mirrored config))
        smoothed-target (smooth-target previous-target
                                       raw-target
                                       (:smoothFactor config))
        delta (target-distance smoothed-target previous-target)
        force? (boolean (:force options))
        base-duration (max 50 (:transitionDurationMs config))
        eye-duration (movement-duration-ms base-duration delta 0.4 50)
        head-duration (movement-duration-ms base-duration delta 0.6 80)]
    {:agency "gaze"
     :rawTarget raw-target
     :target smoothed-target
     :previousTarget previous-target
     :delta delta
     :accepted (or force? (>= delta (:minDelta config)))
     :force force?
     :eyeDurationMs eye-duration
     :headDurationMs head-duration
     :createdAt now-ms}))

(defn candidate-target
  [candidate]
  (let [candidate (data-map candidate)]
    (or (:target candidate)
        (:gazeTarget candidate)
        (:lookTarget candidate)
        (when (or (contains? candidate :x)
                  (contains? candidate :y))
          candidate))))

(defn candidate-score
  [candidate]
  (let [candidate (data-map candidate)]
    (+ (number-or (:priority candidate) 0)
       (number-or (:weight candidate) 0)
       (number-or (:confidence candidate) 0))))

(defn normalize-attention-candidate
  [index candidate]
  (when-let [target (candidate-target candidate)]
    (let [candidate (data-map candidate)]
      {:target (normalize-target target)
       :source (or (:source candidate) (:agency candidate) "attention")
       :label (or (:label candidate) (:id candidate) (str "candidate-" index))
       :score (candidate-score candidate)
       :priority (number-or (:priority candidate) 0)
       :weight (number-or (:weight candidate) 0)
       :confidence (number-or (:confidence candidate) 0)})))

(defn attention-candidates
  [payload]
  (let [payload (data-map payload)
        targets (:targets payload)]
    (cond
      (sequential? targets) targets
      (:target payload) [payload]
      :else [])))

(defn choose-attention-target
  [payload]
  ;; A transducer is useful here because candidate extraction is a pure
  ;; map/filter/reduce pipeline. The planner receives only the winning target
  ;; fact instead of open-coding selection logic around scheduler state.
  (let [candidates (attention-candidates payload)
        normalized (into []
                         (comp (map-indexed normalize-attention-candidate)
                               (filter some?))
                         candidates)]
    (when (seq normalized)
      (apply max-key :score normalized))))

(def conversation-attention-policy
  ;; Gaze owns conversation look-intent policy. The character network only
  ;; forwards conversation.* facts; it must not invent geometry or options.
  {"conversation.userUtterance" {:target {:x 0 :y 0.08 :z 0}
                                 :priority 0.25
                                 :confidence 0.5
                                 :label "conversation-user"
                                 :options {:eyeEnabled true
                                           :headEnabled false}}
   "conversation.agentUtterance" {:target {:x 0 :y 0.04 :z 0}
                                  :priority 0.2
                                  :confidence 0.4
                                  :label "conversation-agent"
                                  :options {:eyeEnabled true
                                            :headEnabled false}}})

(defn conversation-attention-command
  [command]
  (let [payload (data-map command)
        type (:type payload)
        policy (get conversation-attention-policy type)]
    (when policy
      {:type "attention.fact"
       :source "conversation"
       :turnId (:turnId payload)
       :text (:text payload)
       :options (:options policy)
       :targets [(assoc (select-keys policy [:target :priority :confidence :label])
                        :source "conversation")]})))

(defn command-target
  [command]
  (let [payload (data-map command)
        type (:type payload)]
    (case type
      "attention.fact"
      (choose-attention-target payload)

      ("conversation.userUtterance" "conversation.agentUtterance")
      (when-let [attention (conversation-attention-command payload)]
        (choose-attention-target attention))

      "camera.fact"
      (when-let [offset (:relativeOffset payload)]
        {:target (normalize-target offset)
         :source (or (:source payload) "cameraContext")
         :label "camera-relative-offset"
         :score 0.4})

      ("camera.stale" "clearCameraOffset")
      {:target zero-target
       :source (or (:source payload) "cameraContext")
       :label "camera-relative-clear"
       :score 0.4}

      (when-let [target (or (:target payload)
                            (:gazeTarget payload)
                            (:lookTarget payload)
                            (:value payload))]
        {:target (normalize-target target)
         :source (or (:source payload) "command")
         :label (or (:label payload) "direct")
         :score (number-or (:priority payload) 0)}))))
