(ns polymer.transcription.planner
  (:require [clojure.string :as str]
            [polymer.transcription.state :as state]))

;; The planner turns recognition commands and provider callbacks into explicit
;; actions. Provider APIs are not called here; planned provider work is emitted
;; as data so the owning effector boundary can be attached later.

(def supported-command-types
  #{"configure"
    "start"
    "stop"
    "cancel"
    "reset"
    "providerPartial"
    "partialTranscript"
    "providerFinal"
    "finalTranscript"
    "providerError"})

(defn clean-text
  [value]
  (let [text (str/trim (or value ""))]
    (when (pos? (count text))
      text)))

(defn transcript-text
  [command]
  (clean-text (or (:text command) (:transcript command))))

(defn confidence
  [command]
  (state/clamp 0 1 (state/number-or (:confidence command) 1)))

(defn allowed-confidence?
  [command current-state]
  (>= (confidence command) (get-in current-state [:config :minConfidence])))

(defn retry-available?
  [current-state]
  (< (:retryCount current-state) (get-in current-state [:config :maxRetries])))

(defn failure-step
  [command current-state]
  (cond
    (not (contains? supported-command-types (:type command)))
    {:op "fail" :reason "unsupported-command" :commandType (:type command)}

    (and (#{"providerPartial" "partialTranscript" "providerFinal" "finalTranscript"} (:type command))
         (not (transcript-text command)))
    {:op "ignore" :reason "empty-transcript" :commandType (:type command)}

    (and (#{"providerPartial" "partialTranscript" "providerFinal" "finalTranscript"} (:type command))
         (not (allowed-confidence? command current-state)))
    {:op "ignore" :reason "low-confidence" :commandType (:type command)}))

(defn command-steps
  [command current-state now-ms]
  (if-let [failure (failure-step command current-state)]
    [failure]
    (case (:type command)
      "configure" [{:op "apply-config"}
                   {:op "publish-status" :status (:status current-state)}]
      "start" [{:op "start-session"}
               {:op "request-provider-start"}]
      ("stop" "cancel") [{:op "stop-session"
                          :reason (or (:reason command) (:type command))}
                         {:op "request-provider-stop"
                          :reason (or (:reason command) (:type command))}]
      "reset" [{:op "reset-state"}]
      ("providerPartial" "partialTranscript")
      [{:op "publish-partial"
        :text (transcript-text command)
        :confidence (confidence command)
        :source (or (:source command) "provider")}]
      ("providerFinal" "finalTranscript")
      [{:op "publish-final"
        :text (transcript-text command)
        :confidence (confidence command)
        :source (or (:source command) "provider")}]
      "providerError"
      (let [message (or (:message command) (:error command) "provider-error")
            retry? (and (:active current-state)
                        (:continuous (:config current-state))
                        (retry-available? current-state))]
        (cond-> [{:op "record-error"
                  :message message
                  :retry retry?}]
          retry? (conj {:op "request-provider-retry"
                        :message message
                        :retryAfterMs (get-in current-state [:config :retryDelayMs])}))))))

(defn plan-command
  [command current-state now-ms]
  (let [steps (command-steps command current-state now-ms)]
    {:agency "transcription"
     :commandType (:type command)
     :createdAt now-ms
     :ok (not (#{"fail"} (:op (first steps))))
     :steps steps}))
