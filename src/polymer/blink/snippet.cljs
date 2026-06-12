(ns polymer.blink.snippet
  (:require [polymer.blink.planner :as planner]))

(defn blink-curve [start intensity duration randomness]
  (let [random-factor (+ 1 (* (- (rand) 0.5) randomness 0.3))
        final-intensity (min 1 (* intensity random-factor))
        close-time (* duration 0.35)
        hold-time (* duration 0.1)
        open-time (* duration 0.55)]
    [{:time start :intensity 0}
     {:time (+ start (* close-time 0.3)) :intensity (* final-intensity 0.4)}
     {:time (+ start close-time) :intensity final-intensity}
     {:time (+ start close-time hold-time) :intensity (* final-intensity 0.98)}
     {:time (+ start close-time hold-time (* open-time 0.5)) :intensity (* final-intensity 0.5)}
     {:time (+ start close-time hold-time (* open-time 0.85)) :intensity (* final-intensity 0.15)}
     {:time (+ start duration) :intensity 0}]))

(defn build-blink-snippet [blink-state plan now-ms]
  (let [duration (:duration plan)
        gap (:gap plan)
        offsets (map #(* % (+ duration gap)) (range (:count plan)))
        curve (vec (mapcat #(blink-curve % (:intensity plan) duration (:randomness blink-state)) offsets))
        max-time (planner/plan-total-duration plan)
        name-prefix (if (= (:kind plan) :burst) "blink_burst" "blink")]
    {:name (str name-prefix "_" now-ms)
     :curves {"43" curve}
     :maxTime max-time
     :loop false
     :snippetCategory "blink"
     :snippetPriority 100
     :snippetPlaybackRate 1.0
     :snippetIntensityScale 1.0
     :metadata {:polymerAgency "blink"
                :blinkKind (name (:kind plan))
                :blinkCount (:count plan)}}))
