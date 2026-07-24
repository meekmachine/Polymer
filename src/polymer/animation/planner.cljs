(ns polymer.animation.planner
  (:require [polymer.animation.domain :as domain]
            [polymer.animation.state :as state]))

;; The planner turns input stream commands into scheduler actions. It does not
;; call runtimes, mutate handles, or emit events. Keeping those decisions as data
;; makes Animation easier to test and lets future agencies negotiate by sending
;; requests without knowing this agency's internals.

(defn source-agency [payload]
  (or (:sourceAgency payload) "unknown"))

(defn error-action [message]
  {:op :error
   :message message})

(defn schedule-action [payload requested-at]
  (let [source (source-agency payload)
        fallback-name (str "polymer:animation:" requested-at)
        raw-snippet (assoc (:snippet payload)
                           :name (state/snippet-name (:snippet payload) fallback-name))
        ;; LipSync speech must never schedule legacy AU 26 jaw curves.
        snippet (if (= source "lipSync")
                  (domain/sanitize-lipsync-jaw-snippet raw-snippet)
                  raw-snippet)
        options (assoc (or (:options payload) {}) :sourceAgency source)]
    {:op :schedule
     :sourceAgency source
     :requestedAt requested-at
     :snippet snippet
     :options options}))

(defn remove-action [payload]
  {:op :remove
   :sourceAgency (source-agency payload)
   :name (:name payload)
   :reason "requested"})

(defn seek-action [payload]
  {:op :seek
   :sourceAgency (source-agency payload)
   :name (:name payload)
   :offsetSec (max 0 (:offsetSec payload))})

(defn update-action [payload]
  {:op :update
   :sourceAgency (source-agency payload)
   :name (:name payload)
   :params (:params payload)})

(defn clear-action [current-state payload]
  {:op :clear
   :sourceAgency (source-agency payload)
   :names (vec (keys (:scheduled current-state)))})

(defn plan-command [current-state payload requested-at]
  (case (:type payload)
    "scheduleSnippet"
    (if (:snippet payload)
      [(schedule-action payload requested-at)]
      [(error-action "Animation scheduleSnippet command requires a snippet")])

    "removeSnippet"
    (if-let [_name (:name payload)]
      [(remove-action payload)]
      [(error-action "Animation removeSnippet command requires a name")])

    "seekSnippet"
    (cond
      (not (:name payload))
      [(error-action "Animation seekSnippet command requires a name")]

      (not (number? (:offsetSec payload)))
      [(error-action "Animation seekSnippet command requires offsetSec")]

      :else
      [(seek-action payload)])

    "updateSnippet"
    (cond
      (not (:name payload))
      [(error-action "Animation updateSnippet command requires a name")]

      (not (map? (:params payload)))
      [(error-action "Animation updateSnippet command requires params")]

      :else
      [(update-action payload)])

    "clear"
    [(clear-action current-state payload)]

    [(error-action (str "Unknown Animation command: " (:type payload)))]))
