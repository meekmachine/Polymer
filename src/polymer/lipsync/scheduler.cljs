(ns polymer.lipsync.scheduler
  (:require [clojure.string :as str]))

;; LipSync's scheduler owns the effector queue for one utterance.
;;
;; The LipSync agency decides what speech timeline is active and records state.
;; The scheduler owns the ordered work produced by that decision:
;; schedule the combined lip/jaw/tongue snippet, seek it when audio or word
;; boundaries reveal drift, remove it when the utterance is stopped/replaced, and
;; fire the local finish callback after the snippet should be done. It still does
;; not call Embody directly; it emits animation intent as data for Polymer
;; Animation to execute.

(def cleanup-buffer-ms 80)

(defn now-ms []
  (.now js/Date))

(defn finite-positive? [value]
  (and (number? value) (js/isFinite value) (pos? value)))

(def tongue-au-ids #{37 38 39 40 41 42 73 74 76 77})

(defn clear-timeout! [timer-atom]
  (when-let [timer @timer-atom]
    (js/clearTimeout timer)
    (reset! timer-atom nil)))

(defn effector-target [channel]
  (let [target (:target channel)
        target-type (:type target)]
    (case target-type
      "viseme" "lip"
      "lipSync" "jaw"
      "au" (cond
             (contains? tongue-au-ids (:id target)) "tongue"
             :else "au")
      "bone" (case (:id target)
               "JAW" "jaw"
               "TONGUE" "tongue"
               "bone")
      target-type)))

(defn snippet-effectors [snippet]
  (->> (:channels snippet)
       (keep effector-target)
       distinct
       vec))

(defn queue-entry [effect queue-index]
  (assoc effect
         :queueIndex queue-index
         :queuedAt (now-ms)))

(defn create-scheduler [{:keys [emit-event on-finished]}]
  (let [finish-timer (atom nil)
        queue (atom [])
        disposed? (atom false)]
    (letfn [(enqueue! [effect]
              (let [entry (queue-entry effect (count @queue))]
                (swap! queue conj entry)
                entry))

            (schedule-finished! [max-time-sec]
              (clear-timeout! finish-timer)
              (when (and (not @disposed?) (finite-positive? max-time-sec))
                (reset! finish-timer
                        (js/setTimeout
                         (fn []
                           (reset! finish-timer nil)
                           (when-not @disposed?
                             (enqueue! {:type "finishTimeline"
                                        :agency "lipSync"
                                        :reason "completed"})
                             (on-finished)))
                         (+ (* max-time-sec 1000) cleanup-buffer-ms)))))

            (schedule-animation! [snippet-data options]
              (let [effectors (snippet-effectors snippet-data)
                    entry (enqueue! {:type "scheduleAnimation"
                                     :agency "lipSync"
                                     :requestId (:name snippet-data)
                                     :snippetName (:name snippet-data)
                                     :effectors effectors})]
                (emit-event {:type "animation.requestScheduleSnippet"
                             :agency "lipSync"
                             :requestId (:requestId entry)
                             :snippet snippet-data
                             :options options
                             :effectors effectors
                             :queueIndex (:queueIndex entry)
                             :queuedAt (:queuedAt entry)})
                (schedule-finished! (:maxTime snippet-data))))

            (remove-animation! [name reason]
              (let [entry (enqueue! {:type "removeAnimation"
                                     :agency "lipSync"
                                     :requestId name
                                     :name name
                                     :reason reason})]
                (emit-event {:type "animation.requestRemoveSnippet"
                             :agency "lipSync"
                             :requestId name
                             :name name
                             :reason reason
                             :queueIndex (:queueIndex entry)
                             :queuedAt (:queuedAt entry)})))

            (seek-animation! [name offset-sec reason]
              (let [request-id (str name ":seek:" (str/replace (str (now-ms)) "." "_"))
                    entry (enqueue! {:type "seekAnimation"
                                     :agency "lipSync"
                                     :requestId request-id
                                     :name name
                                     :offsetSec offset-sec
                                     :reason reason})]
                (emit-event {:type "animation.requestSeekSnippet"
                             :agency "lipSync"
                             :requestId request-id
                             :name name
                             :offsetSec offset-sec
                             :reason reason
                             :queueIndex (:queueIndex entry)
                             :queuedAt (:queuedAt entry)})))]
      {:schedule-timeline
       (fn [snippet-data options]
         (when-not @disposed?
           (schedule-animation! snippet-data options)))

       :remove
       (fn [name reason]
         (when-not @disposed?
           (remove-animation! name reason)))

       :seek
       (fn [name offset-sec reason]
         (when-not @disposed?
           (seek-animation! name offset-sec reason)))

       :schedule-finished
       (fn [max-time-sec]
         (when-not @disposed?
           (schedule-finished! max-time-sec)))

       :stop
       (fn []
         ;; Replacement/explicit stop cancels only pending local finish. Remove
         ;; requests are separate queued effector operations.
         (clear-timeout! finish-timer))

       :queue
       (fn []
         @queue)

       :dispose
       (fn []
         ;; Disposal is final for this scheduler instance; late callbacks are
         ;; guarded by disposed? even if a host implementation fails to clear.
         (reset! disposed? true)
         (clear-timeout! finish-timer)
         (reset! queue []))})))
