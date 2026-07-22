(ns polymer.prosodic.snippet
  (:require [polymer.prosodic.state :as state]))

;; Prosodic snippet construction is pure data mapping. The agency scheduler
;; emits the resulting map to Polymer Animation; only Animation touches a
;; runtime-specific effector.

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
   :metadata (merge {:agency "prosodic"} metadata)})

(defn gesture-name [gesture-kind now]
  (str "polymer:prosodic:" gesture-kind ":" now))

(defn emphasis-snippet [now config context]
  (base-snippet
   (gesture-name "emphasis" now)
   {"1" [{:time 0.0 :intensity 0}
         {:time 0.12 :intensity 0.42}
         {:time 0.4 :intensity 0.55}
         {:time 0.7 :intensity 0}]
    "2" [{:time 0.0 :intensity 0}
         {:time 0.12 :intensity 0.32}
         {:time 0.4 :intensity 0.42}
         {:time 0.7 :intensity 0}]
    "55" [{:time 0.0 :intensity 0}
          {:time 0.2 :intensity 0.22}
          {:time 0.55 :intensity 0.22}
          {:time 0.85 :intensity 0}]}
   0.85
   (:priority config)
   (* 0.9 (:intensity config))
   (merge {:gesture "emphasis"} context)))

(defn nod-snippet [now config context]
  (base-snippet
   (gesture-name "nod" now)
   {"53" [{:time 0.0 :intensity 0}
          {:time 0.15 :intensity 0.4}
          {:time 0.4 :intensity 0.5}
          {:time 0.75 :intensity 0}]}
   0.75
   (:priority config)
   (* 0.9 (:intensity config))
   (merge {:gesture "nod"} context)))

(defn contemplate-snippet [now config context]
  (base-snippet
   (gesture-name "contemplate" now)
   {"4" [{:time 0.0 :intensity 0}
         {:time 0.2 :intensity 0.18}
         {:time 0.6 :intensity 0.22}
         {:time 1.0 :intensity 0}]
    "1" [{:time 0.0 :intensity 0}
         {:time 0.25 :intensity 0.12}
         {:time 0.65 :intensity 0.12}
         {:time 1.0 :intensity 0}]}
   1.0
   (:priority config)
   (* 0.6 (:intensity config))
   (merge {:gesture "contemplate"} context)))

(defn blink-fast-snippet [now config context]
  ;; Fast blinking gets a small downward head cue. It is no longer hard-coded
  ;; in the character network; Blink emits a signal and Prosodic decides how to
  ;; express that signal.
  (base-snippet
   (gesture-name "blink-fast" now)
   {"1" [{:time 0 :intensity 0}
         {:time 0.12 :intensity 0.26}
         {:time 0.42 :intensity 0.34}
         {:time 0.72 :intensity 0}]
    "2" [{:time 0 :intensity 0}
         {:time 0.12 :intensity 0.2}
         {:time 0.42 :intensity 0.28}
         {:time 0.72 :intensity 0}]
    "54" [{:time 0 :intensity 0}
          {:time 0.16 :intensity 0.16}
          {:time 0.44 :intensity 0.2}
          {:time 0.72 :intensity 0}]}
   0.72
   (max (:priority config) 35)
   (* 0.75 (:intensity config))
   (merge {:gesture "blink-fast" :trigger "blink-fast"} context)))

(defn build-gesture-snippet [gesture-kind config context]
  (let [now (state/now-ms)]
    (case gesture-kind
      "emphasis" (emphasis-snippet now config context)
      "nod" (nod-snippet now config context)
      "contemplate" (contemplate-snippet now config context)
      "blink-fast" (blink-fast-snippet now config context)
      nil)))
