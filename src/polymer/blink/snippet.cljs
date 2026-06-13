(ns polymer.blink.snippet
  (:require [polymer.blink.state :as state]))

;; Snippet construction translates a Blink plan into animation data, but it does
;; not schedule that animation. The output is a plain JS-compatible map that the
;; host interpreter can pass to Latticework during the migration.

(defn pulse-points [offset duration intensity]
  ;; A blink is modeled as a close, brief hold, and open curve on AU 43. Burst
  ;; blinks reuse the same shape at shifted offsets.
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
  ;; Build one animation snippet even for n-blink bursts. This avoids overlapping
  ;; several tiny snippets and gives the scheduler one duration to account for
  ;; before the next automatic blink is planned.
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
  ;; Timer code works in milliseconds while snippets store maxTime in seconds.
  (* 1000 (state/number-or (:maxTime snippet) 0)))
