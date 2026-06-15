(ns polymer.animation.state)

;; Animation state tracks what Polymer has asked the host to schedule.
;;
;; The host still owns the real animation engine during this migration. This
;; state is the agency-local ledger that lets Polymer reason about requested
;; snippets, automatic cleanup, and later cross-agency coordination without
;; reaching into Latticework directly.

(def default-state
  {:agency "animation"
   :scheduled {}
   :scheduledCount 0
   :removedCount 0
   :lastEffect nil})

(defn finite-number? [value]
  (and (number? value) (js/isFinite value)))

(defn now-ms []
  (.now js/Date))

(defn snippet-name [snippet fallback]
  ;; Snippets should normally come with stable names so the host can remove the
  ;; same clip later. Generate a deterministic-enough fallback for defensive JS
  ;; interop, but keep the named-snippet path the normal path.
  (let [name (:name snippet)]
    (if (and (string? name) (pos? (count name)))
      name
      fallback)))

(defn snippet-duration-ms [snippet]
  ;; Animation snippets use seconds. Timers use milliseconds. Non-finite or
  ;; missing maxTime means there is no automatic cleanup timer.
  (when-let [max-time (:maxTime snippet)]
    (when (finite-number? max-time)
      (* 1000 max-time))))

(defn snippet-summary [snippet options source-agency requested-at]
  ;; Store enough state for UI/debugging without copying engine internals into
  ;; React. The full snippet still travels on the effect event to the host.
  {:name (:name snippet)
   :sourceAgency source-agency
   :snippetCategory (:snippetCategory snippet)
   :snippetPriority (:snippetPriority snippet)
   :maxTime (:maxTime snippet)
   :loop (boolean (:loop snippet))
   :autoPlay (boolean (:autoPlay options))
   :requestedAt requested-at})

(defn record-schedule [state snippet options source-agency requested-at]
  (let [summary (snippet-summary snippet options source-agency requested-at)]
    (-> state
        (assoc-in [:scheduled (:name summary)] summary)
        (update :scheduledCount inc)
        (assoc :lastEffect {:type "animation.scheduleSnippet"
                            :name (:name summary)
                            :sourceAgency source-agency
                            :at requested-at}))))

(defn record-remove [state name source-agency removed-at]
  (-> state
      (update :scheduled dissoc name)
      (update :removedCount inc)
      (assoc :lastEffect {:type "animation.removeSnippet"
                          :name name
                          :sourceAgency source-agency
                          :at removed-at})))

(defn visible-state [state]
  (clj->js state))
