(ns polymer.blink.planner
  (:require [polymer.blink.state :as state]))

;; The planner is pure decision logic. It turns incoming commands and blink
;; opportunities into action maps for the agency/scheduler to execute. It does
;; not mutate state, touch clocks, choose random values, or emit stream data.

(def fast-blink-frequency-threshold 36)

(defn random-factor [randomness random-value]
  ;; Randomness stretches or shrinks the next automatic blink interval around
  ;; the frequency-derived baseline.
  (+ 1 (* (- random-value 0.5) randomness)))

(defn next-interval-ms [blink-state random-value]
  ;; Frequency is stored as blinks per minute, so the baseline interval is
  ;; 60/frequency seconds. A disabled scheduler handles whether this value is
  ;; used; the planner only answers the interval question.
  (let [frequency (:frequency blink-state)]
    (if (<= frequency 0)
      nil
      (* (/ 60 frequency) 1000 (random-factor (:randomness blink-state) random-value)))))

(defn trigger-options [options]
  ;; JS callers may omit options. Normalize once so the rest of the planner can
  ;; stay in ordinary Clojure data.
  (if options (js->clj options :keywordize-keys true) {}))

(defn resolve-blink-count [blink-state reason options random-value]
  ;; Manual triggers may request an exact burst count. Automatic triggers choose
  ;; a burst only some of the time, controlled by burstFrequency.
  (let [manual-count (:burstCount options)]
    (cond
      (state/finite-number? manual-count)
      (int (state/clamp 1 8 manual-count))

      (and (= reason "auto")
           (:burstEnabled blink-state)
           (< random-value (:burstFrequency blink-state)))
      (:burstCount blink-state)

      :else 1)))

(defn make-plan [blink-state reason options random-value now-ms]
  ;; A plan is the normalized, immutable description of one blink opportunity.
  ;; Time and randomness are supplied by the scheduler/agency boundary so this
  ;; function remains deterministic and easy to test.
  (let [options (trigger-options options)
        blink-count (resolve-blink-count blink-state reason options random-value)
        duration (state/clamp 0.05 1 (state/number-or (:duration options) (:duration blink-state)))
        gap (state/clamp 0.02 0.5 (state/number-or (:burstGap options) (:burstGap blink-state)))
        intensity (state/clamp 0 1 (state/number-or (:intensity options) (:intensity blink-state)))]
    {:agency "blink"
     :name (str "polymer:blink:" now-ms)
     :reason reason
     :blink-count blink-count
     :duration duration
     :burst-gap gap
     :intensity intensity
     :created-at now-ms
     :fast? (or (>= (:frequency blink-state) fast-blink-frequency-threshold)
                (> blink-count 2))}))

(def config-command-types
  #{"setFrequency"
    "setDuration"
    "setIntensity"
    "setRandomness"
    "setLeftEyeIntensity"
    "setRightEyeIntensity"
    "setBurstEnabled"
    "setBurstFrequency"
    "setBurstCount"
    "setBurstGap"
    "configure"})

(defn error-action [message]
  {:op :error
   :message message})

(defn trigger-action [reason payload random-value now-ms]
  {:op :trigger
   :reason reason
   :options (:options payload)
   :random-value random-value
   :now-ms now-ms})

(defn apply-state-action [payload]
  {:op :apply-state
   :payload payload})

(defn plan-command [blink-state payload random-value now-ms]
  (let [type (:type payload)]
    (cond
      (= "triggerBlink" type)
      [(trigger-action "manual" payload random-value now-ms)]

      (= "requestBlink" type)
      [(trigger-action (or (:reason payload) "request") payload random-value now-ms)]

      (= "enable" type)
      [(apply-state-action payload)
       {:op :refresh-auto}]

      (or (= "disable" type)
          (= "reset" type))
      [(apply-state-action payload)
       {:op :stop-auto}]

      (contains? config-command-types type)
      [(apply-state-action payload)
       {:op :refresh-auto}]

      :else
      [(error-action (str "Unknown Blink command: " type))])))
