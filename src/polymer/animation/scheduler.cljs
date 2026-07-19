(ns polymer.animation.scheduler
  (:require [polymer.animation.domain :as domain]
            [polymer.animation.runtime :as runtime]
            [polymer.animation.state :as state]))

;; The scheduler owns Animation's ordered work queue and runtime handle
;; lifecycle. Other agencies request animation as plain data; only this
;; scheduler decides when to schedule, replace, cancel, seek, or update a
;; runtime clip.

(def default-cleanup-buffer-ms 50)

(defn create-scheduler [{:keys [runtime state-atom emit-event disposed? debug-log! cleanup-buffer-ms]
                         :or {cleanup-buffer-ms default-cleanup-buffer-ms
                              debug-log! (fn [_label _payload] nil)}}]
  (let [cleanup-timers (atom {})
        handles (atom {})
        generation (atom 0)
        queue (atom [])
        draining? (atom false)]
    (letfn [(next-token! []
              (swap! generation inc))

            (handle-for [name]
              (:handle (get @handles name)))

            (current-token? [name token]
              (= token (:token (get @handles name))))

            (clear-cleanup! [name]
              (when-let [entry (get @cleanup-timers name)]
                (js/clearTimeout (:timer entry))
                (swap! cleanup-timers dissoc name)))

            (clear-all-cleanups! []
              (doseq [[_ entry] @cleanup-timers]
                (js/clearTimeout (:timer entry)))
              (reset! cleanup-timers {}))

            (emit-error! [message]
              (emit-event {:type "error"
                           :agency "animation"
                           :message message}))

            (cleanup-runtime! [name]
              (let [handle (handle-for name)]
                (runtime/cleanup-snippet! runtime handle name))
              (swap! handles dissoc name))

            (emit-remove! [name source-agency reason]
              (when (and (not @disposed?)
                         (get-in @state-atom [:scheduled name]))
                (clear-cleanup! name)
                (cleanup-runtime! name)
                (let [removed-at (state/now-ms)]
                  (swap! state-atom state/record-remove name source-agency removed-at reason)
                  (emit-event {:type "animationSnippetRemoved"
                               :agency "animation"
                               :sourceAgency source-agency
                               :reason reason
                               :name name
                               :removedAt removed-at}))))

            (emit-remove-if-current! [name source-agency reason token]
              ;; Runtime handles may resolve after a replacement. The token
              ;; prevents an old completion callback from removing the newer
              ;; snippet that reused the same name.
              (when (current-token? name token)
                (emit-remove! name source-agency reason)))

            (schedule-cleanup! [name snippet source-agency handle token]
              (clear-cleanup! name)
              (when-let [finished (aget handle "finished")]
                (when (runtime/js-callable? (aget finished "then"))
                  (.then finished
                         #(emit-remove-if-current! name source-agency "completed" token)
                         (fn [_error] nil))))
              (when (not (:loop snippet))
                (when-let [duration-ms (state/snippet-duration-ms snippet)]
                  (when (pos? duration-ms)
                    (let [timer (js/setTimeout
                                 #(emit-remove-if-current! name source-agency "completed" token)
                                 (+ duration-ms cleanup-buffer-ms))]
                      (swap! cleanup-timers assoc name {:timer timer
                                                        :token token}))))))

            (schedule-snippet! [{:keys [sourceAgency requestedAt snippet options]}]
              (let [name (:name snippet)
                    token (next-token!)]
                (when (get-in @state-atom [:scheduled name])
                  (emit-remove! name sourceAgency "replaced"))
                (debug-log! "[Polymer Animation CLJS] scheduleSnippet"
                            (domain/schedule-log-payload snippet options sourceAgency))
                (swap! state-atom state/record-schedule snippet options sourceAgency requestedAt)
                (emit-event {:type "animationSnippetScheduled"
                             :agency "animation"
                             :sourceAgency sourceAgency
                             :name name
                             :snippet snippet
                             :options options
                             :requestedAt requestedAt})
                (if runtime
                  (if-let [handle (runtime/play-snippet! runtime snippet options)]
                    (do
                      (swap! handles assoc name {:handle handle
                                                 :token token})
                      (swap! state-atom state/record-start name sourceAgency (state/now-ms))
                      (emit-event {:type "animationSnippetStarted"
                                   :agency "animation"
                                   :sourceAgency sourceAgency
                                   :name name})
                      (schedule-cleanup! name snippet sourceAgency handle token))
                    (emit-error! (str "Animation runtime did not return a clip handle for " name)))
                  (emit-error! "Animation agency requires an animation runtime or engine"))
                snippet))

            (seek-snippet! [{:keys [sourceAgency name offsetSec]}]
              (if (runtime/seek-snippet! runtime (handle-for name) name offsetSec)
                (let [seeked-at (state/now-ms)]
                  (swap! state-atom state/record-seek name sourceAgency seeked-at offsetSec)
                  (emit-event {:type "animationSnippetSeeked"
                               :agency "animation"
                               :sourceAgency sourceAgency
                               :name name
                               :offsetSec offsetSec
                               :seekedAt seeked-at}))
                (emit-error! (str "Animation runtime could not seek " name))))

            (update-snippet! [{:keys [sourceAgency name params]}]
              (if (runtime/update-snippet! runtime (handle-for name) name params)
                (let [updated-at (state/now-ms)]
                  (swap! state-atom state/record-update name sourceAgency updated-at params)
                  (emit-event {:type "animationSnippetUpdated"
                               :agency "animation"
                               :sourceAgency sourceAgency
                               :name name
                               :params params
                               :updatedAt updated-at}))
                (emit-error! (str "Animation runtime could not update " name))))

            (clear! [{:keys [sourceAgency names]}]
              (doseq [name names]
                (emit-remove! name sourceAgency "clear"))
              (clear-all-cleanups!))

            (run-action! [action]
              (case (:op action)
                :schedule (schedule-snippet! action)
                :remove (emit-remove! (:name action) (:sourceAgency action) (:reason action))
                :seek (seek-snippet! action)
                :update (update-snippet! action)
                :clear (clear! action)
                :error (emit-error! (:message action))
                (emit-error! (str "Unknown Animation scheduler action: " (:op action)))))

            (drain! []
              (when-not @draining?
                (reset! draining? true)
                (try
                  (loop []
                    (when-let [action (first @queue)]
                      (swap! queue #(vec (rest %)))
                      (run-action! action)
                      (recur)))
                  (finally
                    (reset! draining? false)))))

            (enqueue-actions! [actions]
              (swap! queue into actions)
              (drain!))

            (dispose! []
              (doseq [name (keys @handles)]
                (cleanup-runtime! name))
              (clear-all-cleanups!)
              (reset! queue []))]
      {:enqueue-actions! enqueue-actions!
       :dispose! dispose!})))
