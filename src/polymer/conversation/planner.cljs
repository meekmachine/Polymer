(ns polymer.conversation.planner
  (:require [clojure.string :as str]
            [polymer.conversation.state :as state]))

;; Conversation planning stays agency-local. It decides what this agency wants
;; to do with a transcript, provider result, or interruption, then hands ordered
;; action data to the scheduler. It never calls a backend, TTS provider, or peer
;; agency function directly.

(def supported-command-types
  #{"configure"
    "start"
    "stop"
    "reset"
    "cancel"
    "interrupt"
    "transcript.final"
    "transcriptFinal"
    "userUtterance"
    "agentUtterance"
    "responseReady"
    "tts.status"})

(defn command-type
  [command]
  (:type command))

(defn clean-text
  [value]
  (let [text (str/trim (or value ""))]
    (when (pos? (count text))
      text)))

(defn user-text
  [command]
  (clean-text (or (:text command)
                  (:transcript command)
                  (:utterance command))))

(defn agent-text
  [command]
  (clean-text (or (:agentText command)
                  (:responseText command)
                  (:text command)
                  (:utterance command))))

(defn request-id
  [prefix now-ms]
  (str prefix ":" now-ms ":" (rand-int 1000000)))

(defn failure-step
  [command]
  (cond
    (not (contains? supported-command-types (command-type command)))
    {:op "fail"
     :reason "unsupported-command"
     :commandType (command-type command)}

    (and (#{"transcript.final" "transcriptFinal" "userUtterance"} (command-type command))
         (not (user-text command)))
    {:op "fail"
     :reason "missing-user-text"
     :commandType (command-type command)}

    (and (#{"agentUtterance" "responseReady"} (command-type command))
         (not (agent-text command)))
    {:op "fail"
     :reason "missing-agent-text"
     :commandType (command-type command)}))

(defn transcript-steps
  [command state now-ms]
  (let [text (user-text command)
        source (or (:source command) "transcription")
        response-text (clean-text (or (:responseText command) (:agentText command)))
        response-request {:requestId (request-id "conversation:response" now-ms)
                          :text text
                          :source source
                          :turnId (:turnId state)
                          :history (:history state)
                          :requestedAt now-ms}]
    (cond-> [{:op "record-user-utterance"
              :text text
              :source source}
             {:op "emit-user-utterance"
              :text text
              :source source}
             {:op "request-response"
              :request response-request}]
      response-text
      (conj {:op "record-agent-utterance"
             :text response-text
             :source "response"}
            {:op "request-tts"
             :text response-text
             :source "response"
             :requestId (request-id "conversation:tts" now-ms)}))))

(defn agent-utterance-steps
  [command now-ms]
  (let [text (agent-text command)]
    [{:op "record-agent-utterance"
      :text text
      :source (or (:source command) "conversation")}
     {:op "request-tts"
      :text text
      :source (or (:source command) "conversation")
      :requestId (request-id "conversation:tts" now-ms)}]))

(defn command-steps
  [command state now-ms]
  (if-let [failure (failure-step command)]
    [failure]
    (case (command-type command)
      "configure" [{:op "apply-config"}
                   {:op "publish-status" :status (:status state)}]
      "start" [{:op "start-session"}
               {:op "publish-status" :status "listening"}]
      "stop" [{:op "stop-session" :reason (or (:reason command) "stopped")}
              {:op "publish-status" :status "idle"}]
      "reset" [{:op "reset-state"}
               {:op "publish-status" :status "idle"}]
      ("cancel" "interrupt") [{:op "cancel-turn"
                               :reason (or (:reason command) (command-type command))}
                              {:op "publish-cancel"
                               :reason (or (:reason command) (command-type command))}
                              {:op "publish-status" :status "interrupted"}]
      ("transcript.final" "transcriptFinal" "userUtterance")
      (transcript-steps command state now-ms)
      ("agentUtterance" "responseReady")
      (agent-utterance-steps command now-ms)
      "tts.status" [{:op "record-tts-status"
                     :status (:status command)}
                    {:op "publish-status" :status (:status command)}])))

(defn plan-command
  [command state now-ms]
  (let [steps (command-steps command state now-ms)]
    {:agency "conversation"
     :commandType (command-type command)
     :createdAt now-ms
     :ok (not= "fail" (:op (first steps)))
     :steps steps}))
