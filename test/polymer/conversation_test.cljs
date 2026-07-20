(ns polymer.conversation-test
  (:require [cljs.test :refer [deftest is testing]]
            [polymer.conversation.agency :as conversation]))

(defn collect-events
  [agency]
  (let [events (atom [])]
    {:events events
     :unsubscribe (.subscribeEvents ^js agency
                                    #(swap! events conj (js->clj % :keywordize-keys true)))}))

(deftest conversation-normalizes-config
  (let [agency (conversation/create-conversation-agency #js {:maxHistory 999
                                                            :autoRespond false})
        snapshot (js->clj (.snapshot ^js agency) :keywordize-keys true)]
    (is (= 200 (get-in snapshot [:config :maxHistory])))
    (is (false? (get-in snapshot [:config :autoRespond])))
    (.dispose ^js agency)))

(deftest start-and-stop-update-turn-state
  (let [agency (conversation/create-conversation-agency nil)
        events (collect-events agency)]
    (.start ^js agency)
    (let [started (js->clj (.snapshot ^js agency) :keywordize-keys true)]
      (is (:started started))
      (is (= "listening" (:status started)))
      (is (some #(and (= "conversation.status" (:type %))
                      (= "listening" (:status %)))
                @(:events events))))
    (.stop ^js agency)
    (let [stopped (js->clj (.snapshot ^js agency) :keywordize-keys true)]
      (is (false? (:started stopped)))
      (is (= "idle" (:status stopped))))
    ((:unsubscribe events))
    (.dispose ^js agency)))

(deftest final-transcript-emits-response-and-tts-requests
  (let [agency (conversation/create-conversation-agency nil)
        events (collect-events agency)]
    (.start ^js agency)
    (.dispatch ^js agency #js {:type "transcriptFinal"
                               :text "hello there"
                               :responseText "hello back"})
    (let [snapshot (js->clj (.snapshot ^js agency) :keywordize-keys true)
          response-request (some #(when (= "conversation.requestResponse" (:type %)) %)
                                 @(:events events))
          tts-request (some #(when (= "tts.requestSpeak" (:type %)) %)
                            @(:events events))]
      (is response-request)
      (is tts-request)
      (is (= "hello there" (:text response-request)))
      (is (= "hello back" (:text tts-request)))
      (is (some #(and (= "conversation.agentUtterance" (:type %))
                      (= "hello back" (:text %)))
                @(:events events)))
      (is (= 1 (:userUtteranceCount snapshot)))
      (is (= 1 (:agentUtteranceCount snapshot)))
      (is (= 1 (:ttsRequestCount snapshot))))
    ((:unsubscribe events))
    (.dispose ^js agency)))

(deftest auto-respond-can-record-user-text-without-requesting-provider
  (let [agency (conversation/create-conversation-agency #js {:autoRespond false})
        events (collect-events agency)]
    (.start ^js agency)
    (.dispatch ^js agency #js {:type "transcriptFinal"
                               :text "just remember this"})
    (let [snapshot (js->clj (.snapshot ^js agency) :keywordize-keys true)]
      (is (= 1 (:userUtteranceCount snapshot)))
      (is (some #(= "conversation.userUtterance" (:type %))
                @(:events events)))
      (is (not-any? #(= "conversation.requestResponse" (:type %))
                    @(:events events))))
    ((:unsubscribe events))
    (.dispose ^js agency)))

(deftest stale-response-after-interrupt-does-not-request-tts
  (let [agency (conversation/create-conversation-agency nil)
        events (collect-events agency)]
    (.start ^js agency)
    (.dispatch ^js agency #js {:type "transcriptFinal"
                               :text "hello"})
    (let [request (some #(when (= "conversation.requestResponse" (:type %)) %)
                        @(:events events))]
      (.interrupt ^js agency "barge-in")
      (.dispatch ^js agency #js {:type "responseReady"
                                 :requestId (:requestId request)
                                 :turnId (:turnId request)
                                 :text "late response"}))
    (is (some #(and (= "conversation.ignored" (:type %))
                    (= "stale-response" (:reason %)))
              @(:events events)))
    (is (not-any? #(and (= "tts.requestSpeak" (:type %))
                        (= "late response" (:text %)))
                  @(:events events)))
    ((:unsubscribe events))
    (.dispose ^js agency)))

(deftest interruption-publishes-cancel-without-host-callbacks
  (let [agency (conversation/create-conversation-agency nil)
        events (collect-events agency)]
    (.start ^js agency)
    (.interrupt ^js agency "barge-in")
    (let [snapshot (js->clj (.snapshot ^js agency) :keywordize-keys true)]
      (is (= "interrupted" (:status snapshot)))
      (is (= 1 (:cancelCount snapshot)))
      (is (some #(and (= "conversation.cancelRequested" (:type %))
                      (= "tts" (:targetAgency %))
                      (= "barge-in" (:reason %)))
                @(:events events))))
    ((:unsubscribe events))
    (.dispose ^js agency)))

(deftest disposed-conversation-stops-emitting
  (let [agency (conversation/create-conversation-agency nil)
        events (collect-events agency)]
    (.dispose ^js agency)
    (.dispatch ^js agency #js {:type "transcriptFinal"
                               :text "ignored"
                               :responseText "ignored"})
    (is (empty? @(:events events)))))
