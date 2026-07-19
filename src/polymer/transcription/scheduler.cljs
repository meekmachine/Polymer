(ns polymer.transcription.scheduler
  (:require [polymer.transcription.state :as state]))

;; The scheduler owns ordering for provider lifecycle and transcript facts. It
;; currently executes immediately, but all provider-facing operations still pass
;; through this queue instead of leaking into UI callbacks.

(defn now-ms
  []
  (.now js/Date))

(defn queue-entry
  [step index plan]
  (assoc step
         :agency "transcription"
         :queueIndex index
         :commandType (:commandType plan)
         :queuedAt (now-ms)))

(defn provider-request
  [state-atom action extra]
  (merge {:type "transcription.requestProvider"
          :agency "transcription"
          :targetAgency "transcription-provider"
          :action action
          :provider (get-in @state-atom [:config :provider])
          :sessionId (:sessionId @state-atom)}
         extra))

(defn transcript-fact
  [state-atom kind step now]
  {:type (str "transcription." kind)
   :agency "transcription"
   :targetAgency "conversation"
   :text (:text step)
   :confidence (:confidence step)
   :isFinal (= kind "final")
   :sequence (state/next-sequence @state-atom)
   :source (:source step)
   :sessionId (:sessionId @state-atom)
   :at now})

(defn emit-status!
  [emit-event state-atom status reason]
  (emit-event (cond-> {:type "transcription.status"
                       :agency "transcription"
                       :status status
                       :sessionId (:sessionId @state-atom)
                       :at (now-ms)}
                reason (assoc :reason reason))))

(defn create-scheduler
  [{:keys [state-atom emit-event]}]
  (let [queue (atom [])
        disposed? (atom false)]
    (letfn [(enqueue! [step plan]
              (let [entry (queue-entry step (count @queue) plan)]
                (swap! queue conj entry)
                entry))

            (execute-step! [step command plan]
              (let [now (now-ms)]
                (enqueue! step plan)
                (case (:op step)
                  "apply-config"
                  (do
                    (swap! state-atom state/configure (:config command))
                    (emit-status! emit-event state-atom (:status @state-atom) "configured"))

                  "start-session"
                  (do
                    (swap! state-atom state/start-session now)
                    (emit-status! emit-event state-atom "listening" nil))

                  "request-provider-start"
                  (emit-event (provider-request state-atom "start"
                                                {:config (:config @state-atom)
                                                 :requestedAt now}))

                  "stop-session"
                  (do
                    (swap! state-atom state/stop-session now (:reason step))
                    (emit-status! emit-event state-atom "idle" (:reason step)))

                  "request-provider-stop"
                  (emit-event (provider-request state-atom "stop"
                                                {:reason (:reason step)
                                                 :requestedAt now}))

                  "reset-state"
                  (do
                    (swap! state-atom state/reset-state)
                    (emit-status! emit-event state-atom "idle" "reset"))

                  "publish-partial"
                  (let [fact (transcript-fact state-atom "partial" step now)]
                    (swap! state-atom state/record-partial fact)
                    (emit-event fact))

                  "publish-final"
                  (let [fact (transcript-fact state-atom "final" step now)]
                    (swap! state-atom state/record-final fact)
                    (emit-event fact))

                  "record-error"
                  (do
                    (swap! state-atom state/record-error (:message step) now (:retry step))
                    (emit-event {:type "transcription.error"
                                 :agency "transcription"
                                 :message (:message step)
                                 :retrying (:retry step)
                                 :sessionId (:sessionId @state-atom)
                                 :at now}))

                  "request-provider-retry"
                  (emit-event (provider-request state-atom "retry"
                                                {:message (:message step)
                                                 :retryAfterMs (:retryAfterMs step)
                                                 :retryCount (:retryCount @state-atom)
                                                 :requestedAt now}))

                  "publish-status"
                  (emit-status! emit-event state-atom (:status step) (:reason step))

                  "ignore"
                  (emit-event {:type "transcription.ignored"
                               :agency "transcription"
                               :reason (:reason step)
                               :commandType (:commandType step)
                               :at now})

                  "fail"
                  (emit-event {:type "error"
                               :agency "transcription"
                               :message (:reason step)
                               :commandType (:commandType step)
                               :at now})

                  nil)))]
      {:schedule
       (fn [command plan]
         (when-not @disposed?
           (doseq [step (:steps plan)]
             (execute-step! step command plan))))

       :queue
       (fn []
         @queue)

       :dispose
       (fn []
         (reset! disposed? true)
         (reset! queue []))})))
