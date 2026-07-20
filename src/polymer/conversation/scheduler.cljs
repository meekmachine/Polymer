(ns polymer.conversation.scheduler
  (:require [polymer.conversation.state :as state]))

;; The Conversation scheduler is the ordered work boundary for the agency. This
;; first slice executes immediately, but it still records every planned step in
;; a queue so future backend/provider sequencing, retries, and cancellation can
;; extend the same architecture without moving logic into host UI code.

(defn now-ms
  []
  (.now js/Date))

(defn queue-entry
  [step index plan]
  (assoc step
         :agency "conversation"
         :queueIndex index
         :commandType (:commandType plan)
         :queuedAt (now-ms)))

(defn emit-status!
  [emit-event state-atom status reason]
  (emit-event (cond-> {:type "conversation.status"
                       :agency "conversation"
                       :status status
                       :turnId (:turnId @state-atom)
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

            (request-tts! [step requested-at]
              (let [request {:type "tts.requestSpeak"
                             :agency "conversation"
                             :targetAgency (get-in @state-atom [:config :ttsAgency])
                             :requestId (:requestId step)
                             :text (:text step)
                             :source (:source step)
                             :turnId (:turnId @state-atom)
                             :requestedAt requested-at
                             :command {:type "speak"
                                       :text (:text step)
                                       :source "conversation"}}]
                (swap! state-atom state/record-tts-request request)
                (emit-event request)))

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
                    (swap! state-atom state/mark-started now)
                    (emit-status! emit-event state-atom "listening" nil))

                  "stop-session"
                  (do
                    (swap! state-atom state/mark-stopped now (:reason step))
                    (emit-status! emit-event state-atom "idle" (:reason step)))

                  "reset-state"
                  (do
                    (swap! state-atom state/reset-state)
                    (emit-status! emit-event state-atom "idle" "reset"))

                  "record-user-utterance"
                  (swap! state-atom state/record-user-utterance (:text step) now (:source step))

                  "emit-user-utterance"
                  (emit-event {:type "conversation.userUtterance"
                               :agency "conversation"
                               :text (:text step)
                               :source (:source step)
                               :turnId (:turnId @state-atom)
                               :at now})

                  "request-response"
                  (let [request (:request step)]
                    (swap! state-atom state/record-response-request request)
                    (emit-event (assoc request
                                       :type "conversation.requestResponse"
                                       :agency "conversation"
                                       :targetAgency "conversation-provider")))

                  "record-agent-utterance"
                  (swap! state-atom state/record-agent-utterance (:text step) now (:source step))

                  "request-tts"
                  (request-tts! step now)

                  "cancel-turn"
                  (swap! state-atom state/record-cancel now (:reason step))

                  "publish-cancel"
                  (emit-event {:type "conversation.cancelRequested"
                               :agency "conversation"
                               :targetAgency (get-in @state-atom [:config :ttsAgency])
                               :reason (:reason step)
                               :turnId (:turnId @state-atom)
                               :at now})

                  "ignore-stale-response"
                  (emit-event {:type "conversation.ignored"
                               :agency "conversation"
                               :reason (:reason step)
                               :requestId (:requestId step)
                               :turnId (:turnId step)
                               :at now})

                  "record-tts-status"
                  (swap! state-atom assoc
                         :status (or (:status step) (:status @state-atom))
                         :lastEvent {:type "conversation.ttsStatus"
                                     :status (:status step)
                                     :at now})

                  "publish-status"
                  (emit-status! emit-event state-atom (:status step) (:reason step))

                  "fail"
                  (emit-event {:type "error"
                               :agency "conversation"
                               :message (:reason step)
                               :commandType (:commandType step)})

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
