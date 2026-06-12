(ns polymer.blink.snippet
  (:require [polymer.blink.state :as state]))

(defn pulse-points [offset duration intensity]
  (let [close-time (* duration 0.35)
        hold-time (* duration 0.1)
        open-time (* duration 0.55)]
    [{:time offset :intensity 0}
     {:time (+ offset (* close-time 0.3)) :intensity (* intensity 0.4)}
     {:time (+ offset close-time) :intensity intensity}
     {:time (+ offset close-time hold-time) :intensity (* intensity 0.98)}
     {:time (+ offset close-time hold-time (* open-time 0.5)) :intensity (* intensity 0.5)}
     {:time (+ offset close-time hold-time (* open-time 0.85)) :intensity (* intensity 0.15)}
     {:time (+ offset duration) :intensity 0}]))

(defn build-blink-snippet [plan]
  (let [blink-count (:blink-count plan)
        duration (:duration plan)
        gap (:burst-gap plan)
        intensity (:intensity plan)
        max-time (+ (* blink-count duration) (* (max 0 (dec blink-count)) gap))
        points (->> (range blink-count)
                    (mapcat (fn [index]
                              (pulse-points (* index (+ duration gap)) duration intensity)))
                    vec)]
    {:name (:name plan)
     :curves {"43" points}
     :maxTime max-time
     :loop false
     :snippetCategory "blink"
     :snippetPriority 100
     :snippetPlaybackRate 1
     :snippetIntensityScale 1
     :metadata {:agency "blink"
                :blinkCount blink-count
                :burst (> blink-count 1)}}))

(defn snippet-duration-ms [snippet]
  (* 1000 (state/number-or (:maxTime snippet) 0)))
