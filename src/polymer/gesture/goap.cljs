(ns polymer.gesture.goap
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [polymer.gesture.state :as state]))

;; Gesture planning is intentionally local. It resolves a command into gesture
;; objectives, chooses authored gesture data, and proposes scheduler work. It
;; does not build snippets, mutate state, or call Animation/Embody directly.
;;
;; The planned goal is: reach a state where one suitable authored arm/hand
;; gesture is scheduled through Animation without violating enabled/cooldown,
;; active-effector conflict, or max-active constraints. If that state cannot be
;; reached, the plan records the refusal reason instead of letting the host or
;; Animation guess.

(def supported-command-types
  #{"configure"
    "loadGestures"
    "playGesture"
    "playEmoji"
    "gesture.goal"
    "performGestureGoal"
    "playGoal"
    "stopGesture"
    "stopAll"
    "reset"})

(def playable-command-types
  #{"playGesture" "playEmoji" "gesture.goal" "performGestureGoal" "playGoal"})

(def goal-command-types
  #{"gesture.goal" "performGestureGoal" "playGoal"})

(defn lower-string [value]
  (some-> value state/clean-string str/lower-case))

(defn lower-strings [values]
  (->> (or values [])
       (keep lower-string)
       distinct
       vec))

(defn goal-input [command]
  (merge (dissoc command :type :goal)
         (dissoc (or (state/js-data (:goal command)) {}) :type)))

(defn command-gesture-id [command world]
  (let [input (goal-input command)]
    (case (:type command)
      "playGesture" (state/clean-string (or (:gestureId input) (:id input)))
      "stopGesture" (state/clean-string (or (:gestureId command) (:id command)))
      "playEmoji" (get (:emojiMappings world) (state/clean-string (:emoji input)))
      (state/clean-string (or (:gestureId input) (:id input))))))

(defn preferred-gesture-id [command]
  (let [input (goal-input command)]
    (state/clean-string (or (:preferredGestureId input)
                            (:preferredId input)
                            (:preferredGesture input)))))

(defn hard-gesture-id? [command]
  (or (= "playGesture" (:type command))
      (boolean (some? (state/clean-string (or (:gestureId (goal-input command))
                                           (:id (goal-input command))))))))

(defn goal-present? [goal]
  (or (:gestureId goal)
      (:preferredGestureId goal)
      (:emoji goal)
      (:intent goal)
      (seq (:tags goal))
      (seq (:requiredTags goal))
      (:scope goal)
      (seq (:affectedBones goal))
      (seq (:requiredBones goal))
      (seq (:avoidBones goal))
      (:sourceText goal)
      (:textRepresentation goal)))

(defn command-goal [command world now]
  (let [type (:type command)
        input (goal-input command)
        gesture-id (command-gesture-id command world)
        preferred-id (preferred-gesture-id command)
        emoji (state/clean-string (or (:emoji input) (:emoji command)))
        hard-id? (or (hard-gesture-id? command)
                     (and (= "playEmoji" type) gesture-id))]
    {:type type
     :commandType type
     :now now
     :enabled (get-in world [:config :enabled])
     :gestureId gesture-id
     :hardGestureId hard-id?
     :preferredGestureId preferred-id
     :emoji emoji
     :intent (lower-string (or (:intent input) (:kind input) (:purpose input)))
     :tags (lower-strings (:tags input))
     :requiredTags (lower-strings (or (:requiredTags input) (:mustHaveTags input)))
     :scope (lower-string (:scope input))
     :affectedBones (state/clean-strings (:affectedBones input))
     :requiredBones (state/clean-strings (:requiredBones input))
     :avoidBones (state/clean-strings (:avoidBones input))
     :sourceText (lower-string (:sourceText input))
     :textRepresentation (lower-string (:textRepresentation input))
     :name (state/clean-string (:name input))
     :intensity (:intensity input)
     :trigger (or (:trigger input)
                  (case type
                    "playEmoji" "emoji"
                    "playGesture" "explicit"
                    "gesture.goal" "goal"
                    "performGestureGoal" "goal"
                    "playGoal" "goal"
                    "command"))
     :activeNames (state/active-names world)
     :activeSnippets (:activeSnippets world)
     :activeGestureNames (state/active-names-for-gesture world gesture-id)
     :replaceActive (get-in world [:config :replaceActive])
     :maxActive (get-in world [:config :maxActive])
     :gestureCount (count (:gestures world))
     :config (:config world)}))

(defn gesture-has-motion? [gesture]
  (or (seq (:keyframes gesture))
      (seq (:bones gesture))))

(defn gesture-terms [gesture]
  (lower-strings (concat [(:id gesture)
                          (:name gesture)
                          (:description gesture)
                          (:sourceText gesture)
                          (:textRepresentation gesture)
                          (:emoji gesture)
                          (:scope gesture)]
                         (:tags gesture)
                         (:affectedBones gesture))))

(defn includes-term? [terms needle]
  (and needle
       (some #(or (= needle %)
                  (str/includes? % needle))
             terms)))

(defn goal-text-terms [goal]
  (->> [(:intent goal) (:sourceText goal) (:textRepresentation goal)]
       (keep identity)
       distinct
       vec))

(defn matching-goal-text [goal terms]
  (->> (goal-text-terms goal)
       (filter #(includes-term? terms %))
       vec))

(defn intersects? [left right]
  (boolean (seq (set/intersection (set (or left []))
                                  (set (or right []))))))

(defn tags-match? [goal-tags gesture-tags]
  (let [gesture-tag-set (set gesture-tags)]
    (every? gesture-tag-set goal-tags)))

(defn reject-reason [goal gesture-id gesture world]
  (let [terms (gesture-terms gesture)
        gesture-tags (lower-strings (:tags gesture))
        affected-bones (state/clean-strings (or (:affectedBones gesture) (keys (:bones gesture))))]
    (cond
      (and (:hardGestureId goal) (not= (:gestureId goal) gesture-id))
      "not-requested-gesture"

      (not gesture)
      "unknown-gesture"

      (not (gesture-has-motion? gesture))
      "gesture-has-no-motion"

      (and (seq (:requiredTags goal))
           (not (tags-match? (:requiredTags goal) gesture-tags)))
      "required-tags"

      (and (seq (:requiredBones goal))
           (not (every? (set affected-bones) (:requiredBones goal))))
      "required-bones"

      (and (seq (:avoidBones goal))
           (intersects? (:avoidBones goal) affected-bones))
      "avoided-bones"

      (and (:emoji goal)
           (not (:hardGestureId goal))
           (not= (:emoji goal) (state/clean-string (:emoji gesture))))
      "emoji-mismatch"

      (and (not (:hardGestureId goal))
           (seq (goal-text-terms goal))
           (empty? (matching-goal-text goal terms))
           (empty? (set/intersection (set (:tags goal)) (set gesture-tags))))
      "goal-text-mismatch"

      (not (state/gesture-ready? world gesture-id (:now goal)))
      "cooldown"

      :else nil)))

(defn gesture-priority [goal gesture]
  (state/number-or (:priority gesture) (:priority (:config goal))))

(defn recency-penalty [world gesture-id now]
  (let [last-played (get-in world [:lastPlayedByGesture gesture-id])]
    (if-not last-played
      0
      (* 15 (max 0 (- 1 (/ (- now last-played) 5000)))))))

(defn candidate-score [goal gesture-id gesture world]
  (let [terms (gesture-terms gesture)
        gesture-tags (lower-strings (:tags gesture))
        affected-bones (state/clean-strings (or (:affectedBones gesture) (keys (:bones gesture))))
        text-matches (matching-goal-text goal terms)
        priority (gesture-priority goal gesture)
        tag-matches (count (set/intersection (set (:tags goal)) (set gesture-tags)))
        required-tag-matches (count (set/intersection (set (:requiredTags goal)) (set gesture-tags)))
        bone-matches (count (set/intersection (set (:affectedBones goal)) (set affected-bones)))
        scope-match? (and (:scope goal) (= (:scope goal) (lower-string (:scope gesture))))
        score (+ (if (= (:gestureId goal) gesture-id) 1000 0)
                 (if (= (:preferredGestureId goal) gesture-id) 300 0)
                 (if (and (:emoji goal) (= (:emoji goal) (state/clean-string (:emoji gesture)))) 220 0)
                 (if (includes-term? terms (:intent goal)) 160 0)
                 (* 80 (count (remove #{(:intent goal)} text-matches)))
                 (* 45 tag-matches)
                 (* 55 required-tag-matches)
                 (* 35 bone-matches)
                 (if scope-match? 30 0)
                 (if (and (:scope goal) (= "both" (lower-string (:scope gesture)))) 10 0)
                 (min 50 (max -50 priority))
                 (- (recency-penalty world gesture-id (:now goal))))]
    {:gestureId gesture-id
     :score score
     :priority priority
     :matches {:intent (when (includes-term? terms (:intent goal)) (:intent goal))
               :text text-matches
               :tags (vec (set/intersection (set (:tags goal)) (set gesture-tags)))
               :requiredTags (vec (set/intersection (set (:requiredTags goal)) (set gesture-tags)))
               :scope (when scope-match? (:scope goal))
               :bones (vec (set/intersection (set (:affectedBones goal)) (set affected-bones)))
               :emoji (when (and (:emoji goal) (= (:emoji goal) (state/clean-string (:emoji gesture))))
                        (:emoji goal))}}))

(defn candidates [goal world]
  (->> (:gestures world)
       (keep (fn [[gesture-id gesture]]
               (when-not (reject-reason goal gesture-id gesture world)
                 (assoc (candidate-score goal gesture-id gesture world)
                        :gesture gesture))))
       (sort-by (juxt (comp - :score) (comp - :priority) :gestureId))
       vec))

(defn selected-candidate [goal world]
  (first (candidates goal world)))

(defn failure-step [goal]
  (cond
    (not (contains? supported-command-types (:type goal)))
    {:op "fail" :reason "unsupported-command" :commandType (:type goal)}

    (and (contains? playable-command-types (:type goal))
         (not (:enabled goal)))
    {:op "ignore" :reason "disabled"}

    (and (= "playEmoji" (:type goal))
         (not (:emoji goal)))
    {:op "fail" :reason "missing-emoji"}

    (and (contains? goal-command-types (:type goal))
         (not (goal-present? goal)))
    {:op "fail" :reason "missing-goal"}

    (and (#{"playGesture" "stopGesture"} (:type goal))
         (not (:gestureId goal)))
    {:op "fail" :reason "missing-gesture"}

    :else nil))

(defn candidate-failure-step [goal world]
  (let [hard-id (:gestureId goal)
        hard-gesture (get (:gestures world) hard-id)]
    (cond
      (and hard-id (not hard-gesture))
      {:op "fail" :reason "unknown-gesture" :gestureId hard-id}

      (and hard-gesture (not (gesture-has-motion? hard-gesture)))
      {:op "fail" :reason "gesture-has-no-motion" :gestureId hard-id}

      (and hard-id (not (state/gesture-ready? world hard-id (:now goal))))
      {:op "ignore" :reason "cooldown" :gestureId hard-id}

      (zero? (:gestureCount goal))
      {:op "fail" :reason "empty-library"}

      :else
      {:op "ignore" :reason "no-gesture-candidate"})))

(defn active-conflicts [goal gesture]
  (let [affected-bones (state/clean-strings (or (:affectedBones gesture) (keys (:bones gesture))))]
    (->> (:activeSnippets goal)
         (keep (fn [[name active]]
                 (when (intersects? affected-bones (:affectedBones active))
                   (assoc active :name name))))
         vec)))

(defn capacity-removals [goal conflict-names]
  (let [remaining (->> (:activeSnippets goal)
                       (remove (fn [[name _]] (contains? (set conflict-names) name)))
                       (map second)
                       (sort-by :scheduledAt)
                       vec)
        overflow (max 0 (- (inc (count remaining)) (:maxActive goal)))]
    (->> remaining
         (take overflow)
         (map :name)
         vec)))

(defn active-resolution-steps [goal gesture]
  (let [conflicts (active-conflicts goal gesture)
        conflict-names (mapv :name conflicts)]
    (cond
      (and (seq conflict-names) (not (:replaceActive goal)))
      [{:op "ignore"
        :reason "active-conflict"
        :names conflict-names}]

      (and (not (:replaceActive goal))
           (>= (count (:activeNames goal)) (:maxActive goal)))
      [{:op "ignore" :reason "max-active"}]

      :else
      (let [capacity-names (capacity-removals goal conflict-names)]
        (cond-> []
          (seq conflict-names)
          (conj {:op "remove-active-gestures"
                 :names conflict-names
                 :reason "conflict"})

          (seq capacity-names)
          (conj {:op "remove-active-gestures"
                 :names capacity-names
                 :reason "capacity"}))))))

(defn gesture-context [goal candidate]
  (cond-> {:name (:name goal)
           :intensity (:intensity goal)
           :trigger (:trigger goal)
           :intent (:intent goal)
           :goal {:intent (:intent goal)
                  :emoji (:emoji goal)
                  :tags (:tags goal)
                  :requiredTags (:requiredTags goal)
                  :scope (:scope goal)
                  :affectedBones (:affectedBones goal)
                  :requiredBones (:requiredBones goal)
                  :avoidBones (:avoidBones goal)}
           :selection {:gestureId (:gestureId candidate)
                       :score (:score candidate)
                       :matches (:matches candidate)}}
    (nil? (:name goal)) (dissoc :name)
    (nil? (:intensity goal)) (dissoc :intensity)
    (nil? (:intent goal)) (update :goal dissoc :intent)
    (nil? (:emoji goal)) (update :goal dissoc :emoji)
    (empty? (:tags goal)) (update :goal dissoc :tags)
    (empty? (:requiredTags goal)) (update :goal dissoc :requiredTags)
    (nil? (:scope goal)) (update :goal dissoc :scope)
    (empty? (:affectedBones goal)) (update :goal dissoc :affectedBones)
    (empty? (:requiredBones goal)) (update :goal dissoc :requiredBones)
    (empty? (:avoidBones goal)) (update :goal dissoc :avoidBones)))

(defn playable-steps [goal world]
  (if-let [failure (failure-step goal)]
    [failure]
    (let [candidate (selected-candidate goal world)
          gesture (:gesture candidate)]
      (if-not candidate
        [(candidate-failure-step goal world)]
        (let [resolution (active-resolution-steps goal gesture)]
          (if (= "ignore" (:op (first resolution)))
            [{:op "resolve-gesture-goal"
              :goal (dissoc goal :activeSnippets :config)}
             {:op "select-gesture"
              :gestureId (:gestureId candidate)
              :score (:score candidate)
              :matches (:matches candidate)}
             (first resolution)]
            (vec (concat [{:op "resolve-gesture-goal"
                           :goal (dissoc goal :activeSnippets :config)}
                          {:op "select-gesture"
                           :gestureId (:gestureId candidate)
                           :score (:score candidate)
                           :matches (:matches candidate)}]
                         resolution
                         [{:op "build-gesture-snippet"
                           :gestureId (:gestureId candidate)
                           :context (gesture-context goal candidate)}
                          {:op "schedule-animation"
                           :targetAgency "animation"}
                          {:op "record-schedule"}]))))))))

(defn stop-steps [goal]
  [{:op "remove-active-gestures"
    :names (:activeGestureNames goal)
    :reason "requested"}])

(defn command-steps [goal]
  (if-let [failure (failure-step goal)]
    [failure]
    (case (:type goal)
      "configure" [{:op "apply-config"}]
      "loadGestures" [{:op "load-gestures"}]
      "reset" [{:op "remove-active-gestures"
                :names (:activeNames goal)
                :reason "reset"}
               {:op "reset-state"}]
      "stopAll" [{:op "remove-active-gestures"
                  :names (:activeNames goal)
                  :reason "requested"}]
      "stopGesture" (stop-steps goal)
      ("playGesture" "playEmoji" "gesture.goal" "performGestureGoal" "playGoal")
      ::playable
      [{:op "fail" :reason "unsupported-command" :commandType (:type goal)}])))

(defn plan-ok? [steps]
  (not-any? #(= "fail" (:op %)) steps))

(defn noop? [steps]
  (boolean (some #(= "ignore" (:op %)) steps)))

(defn remove-names [steps]
  (->> steps
       (keep (fn [step]
               (when (= "remove-active-gestures" (:op step))
                 (:names step))))
       (apply concat)
       vec))

(defn remove-reason [steps]
  (or (:reason (some #(when (= "remove-active-gestures" (:op %)) %) steps))
      "requested"))

(defn plan-command [command world now]
  (let [goal (command-goal command world now)
        steps (if (contains? playable-command-types (:type goal))
                (playable-steps goal world)
                (command-steps goal))
        candidate-step (some #(when (= "select-gesture" (:op %)) %) steps)
        build-step (some #(when (= "build-gesture-snippet" (:op %)) %) steps)]
    {:goal goal
     :steps steps
     :ok (plan-ok? steps)
     :noop (noop? steps)
     :gestureId (:gestureId candidate-step)
     :gesture (get (:gestures world) (:gestureId candidate-step))
     :context (:context build-step)
     :removeNames (remove-names steps)
     :removeReason (remove-reason steps)}))
