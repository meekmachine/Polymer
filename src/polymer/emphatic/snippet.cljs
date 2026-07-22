(ns polymer.emphatic.snippet
  (:require [polymer.emphatic.state :as state]))

(defn au-channel [au-id curve]
  {:target {:type "au" :id au-id}
   :keyframes curve})

(defn base-snippet [name curves max-time priority intensity metadata]
  {:name name
   :curves curves
   :channels (mapv (fn [[au-id curve]]
                     (au-channel (js/parseInt (str au-id) 10) curve))
                   curves)
   :maxTime max-time
   :loop false
   :snippetPriority priority
   :snippetPlaybackRate 1
   :snippetIntensityScale intensity
   :snippetBlendMode "additive"
   :mixerClampWhenFinished true
   :metadata (merge {:agency "emphatic"} metadata)})

(defn gesture-name [gesture-kind now]
  (str "polymer:emphatic:" gesture-kind ":" now))

(defn brow-raise-snippet [now config context]
  (base-snippet
   (gesture-name "raise" now)
   {"1" [{:time 0.0 :intensity 0}
         {:time 0.1 :intensity 0.55}
         {:time 0.35 :intensity 0.72}
         {:time 0.7 :intensity 0}]
    "2" [{:time 0.0 :intensity 0}
         {:time 0.1 :intensity 0.48}
         {:time 0.35 :intensity 0.62}
         {:time 0.7 :intensity 0}]}
   0.7
   (:priority config)
   (* 1.0 (:intensity config))
   (merge {:gesture "raise" :channel "brow"} context)))

(defn brow-furrow-snippet [now config context]
  (base-snippet
   (gesture-name "furrow" now)
   {"4" [{:time 0.0 :intensity 0}
         {:time 0.12 :intensity 0.45}
         {:time 0.4 :intensity 0.58}
         {:time 0.75 :intensity 0}]
    "1" [{:time 0.0 :intensity 0}
         {:time 0.15 :intensity 0.18}
         {:time 0.45 :intensity 0.18}
         {:time 0.75 :intensity 0}]}
   0.75
   (:priority config)
   (* 0.95 (:intensity config))
   (merge {:gesture "furrow" :channel "brow"} context)))

(defn brow-flash-snippet [now config context]
  (base-snippet
   (gesture-name "flash" now)
   {"1" [{:time 0.0 :intensity 0}
         {:time 0.08 :intensity 0.65}
         {:time 0.22 :intensity 0.78}
         {:time 0.45 :intensity 0}]
    "2" [{:time 0.0 :intensity 0}
         {:time 0.08 :intensity 0.58}
         {:time 0.22 :intensity 0.7}
         {:time 0.45 :intensity 0}]}
   0.45
   (:priority config)
   (* 1.05 (:intensity config))
   (merge {:gesture "flash" :channel "brow"} context)))

(defn head-nod-snippet [now config context]
  (base-snippet
   (gesture-name "nod" now)
   {"53" [{:time 0.0 :intensity 0}
          {:time 0.12 :intensity 0.55}
          {:time 0.35 :intensity 0.68}
          {:time 0.7 :intensity 0}]}
   0.7
   (:priority config)
   (* 1.0 (:intensity config))
   (merge {:gesture "nod" :channel "head"} context)))

(defn head-tilt-snippet [now config context]
  (base-snippet
   (gesture-name "tilt" now)
   {"54" [{:time 0.0 :intensity 0}
          {:time 0.15 :intensity 0.42}
          {:time 0.45 :intensity 0.5}
          {:time 0.8 :intensity 0}]}
   0.8
   (:priority config)
   (* 0.95 (:intensity config))
   (merge {:gesture "tilt" :channel "head"} context)))

(defn head-turn-snippet [now config context]
  (base-snippet
   (gesture-name "turn" now)
   {"55" [{:time 0.0 :intensity 0}
          {:time 0.18 :intensity 0.38}
          {:time 0.5 :intensity 0.48}
          {:time 0.85 :intensity 0}]}
   0.85
   (:priority config)
   (* 0.9 (:intensity config))
   (merge {:gesture "turn" :channel "head"} context)))

(defn build-gesture-snippet [gesture-kind config context]
  (let [now (state/now-ms)]
    (case gesture-kind
      "raise" (brow-raise-snippet now config context)
      "furrow" (brow-furrow-snippet now config context)
      "flash" (brow-flash-snippet now config context)
      "nod" (head-nod-snippet now config context)
      "tilt" (head-tilt-snippet now config context)
      "turn" (head-turn-snippet now config context)
      (brow-raise-snippet now config context))))
