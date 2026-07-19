(ns polymer.animation.state)

;; Animation state is the agency-local schedule ledger.
;;
;; The Animation agency owns this schedule state and directly talks to the
;; injected animation runtime. Other agencies can inspect summary state and
;; subscribe to events, but they do not reach through to runtime handles.

(def default-state
  {:agency "animation"
   :scheduled {}
   :scheduledCount 0
   :startedCount 0
   :seekCount 0
   :updatedCount 0
   :removedCount 0
   :lastEvent nil})

(defn finite-number? [value]
  (and (number? value) (js/isFinite value)))

(defn now-ms []
  (.now js/Date))

(defn snippet-name [snippet fallback]
  ;; Snippets should normally come with stable names so the runtime can clean up
  ;; the same clip later. Generate a fallback only for defensive JS interop.
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
  ;; Store enough state for diagnostics without copying runtime handles into
  ;; snapshots. Handles stay private inside the Animation agency.
  {:name (:name snippet)
   :sourceAgency source-agency
   :snippetCategory (:snippetCategory snippet)
   :snippetPriority (:snippetPriority snippet)
   :maxTime (:maxTime snippet)
   :loop (boolean (:loop snippet))
   :autoPlay (boolean (:autoPlay options))
   :requestedAt requested-at
   :startedAt nil
   :lastUpdateAt nil
   :lastUpdateParams nil
   :removedAt nil
   :removeReason nil})

(defn record-schedule [state snippet options source-agency requested-at]
  (let [summary (snippet-summary snippet options source-agency requested-at)]
    (-> state
        (assoc-in [:scheduled (:name summary)] summary)
        (update :scheduledCount inc)
        (assoc :lastEvent {:type "animationSnippetScheduled"
                           :name (:name summary)
                           :sourceAgency source-agency
                           :at requested-at}))))

(defn record-start [state name source-agency started-at]
  (-> state
      (assoc-in [:scheduled name :startedAt] started-at)
      (update :startedCount inc)
      (assoc :lastEvent {:type "animationSnippetStarted"
                         :name name
                         :sourceAgency source-agency
                         :at started-at})))

(defn record-remove [state name source-agency removed-at reason]
  (-> state
      (update :scheduled dissoc name)
      (update :removedCount inc)
      (assoc :lastEvent {:type "animationSnippetRemoved"
                         :name name
                         :sourceAgency source-agency
                         :reason reason
                         :at removed-at})))

(defn record-seek [state name source-agency seeked-at offset-sec]
  ;; Seeking is still schedule state: the Animation agency owns the active
  ;; snippet handles, so drift correction from LipSync comes here instead
  ;; of reaching through LoomLarge or bypassing Polymer's agency boundary.
  (-> state
      (assoc-in [:scheduled name :lastSeekAt] seeked-at)
      (assoc-in [:scheduled name :lastSeekOffsetSec] offset-sec)
      (update :seekCount inc)
      (assoc :lastEvent {:type "animationSnippetSeeked"
                         :name name
                         :sourceAgency source-agency
                         :offsetSec offset-sec
                         :at seeked-at})))

(defn record-update [state name source-agency updated-at params]
  (-> state
      (assoc-in [:scheduled name :lastUpdateAt] updated-at)
      (assoc-in [:scheduled name :lastUpdateParams] params)
      (update :updatedCount inc)
      (assoc :lastEvent {:type "animationSnippetUpdated"
                         :name name
                         :sourceAgency source-agency
                         :params params
                         :at updated-at})))

(defn visible-state [state]
  (clj->js state))
