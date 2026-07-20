(ns polymer.transcription-test
  (:require [cljs.test :refer [deftest is testing]]
            [polymer.transcription.agency :as transcription]))

(defn collect-events
  [agency]
  (let [events (atom [])]
    {:events events
     :unsubscribe (.subscribeEvents ^js agency
                                    #(swap! events conj (js->clj % :keywordize-keys true)))}))

(deftest transcription-normalizes-config
  (let [agency (transcription/create-transcription-agency #js {:maxAlternatives 25
                                                              :maxRetries 99
                                                              :minConfidence -1})
        snapshot (js->clj (.snapshot ^js agency) :keywordize-keys true)]
    (is (= 10 (get-in snapshot [:config :maxAlternatives])))
    (is (= 10 (get-in snapshot [:config :maxRetries])))
    (is (= 0 (get-in snapshot [:config :minConfidence])))
    (.dispose ^js agency)))

(deftest start-stop-emit-provider-lifecycle-requests
  (let [agency (transcription/create-transcription-agency nil)
        events (collect-events agency)]
    (.start ^js agency)
    (let [started (js->clj (.snapshot ^js agency) :keywordize-keys true)]
      (is (:active started))
      (is (= "listening" (:status started)))
      (is (some #(and (= "transcription.requestProvider" (:type %))
                      (= "start" (:action %)))
                @(:events events))))
    (.stop ^js agency)
    (let [stopped (js->clj (.snapshot ^js agency) :keywordize-keys true)]
      (is (false? (:active stopped)))
      (is (= "idle" (:status stopped)))
      (is (some #(and (= "transcription.requestProvider" (:type %))
                      (= "stop" (:action %)))
                @(:events events))))
    ((:unsubscribe events))
    (.dispose ^js agency)))

(deftest partial-and-final-transcripts-are-ordered-facts
  (let [agency (transcription/create-transcription-agency nil)
        events (collect-events agency)]
    (.start ^js agency)
    (.dispatch ^js agency #js {:type "providerPartial" :text "hello" :confidence 0.8})
    (.dispatch ^js agency #js {:type "providerFinal" :text "hello world" :confidence 0.9})
    (let [partial (some #(when (= "transcription.partial" (:type %)) %) @(:events events))
          final (some #(when (= "transcription.final" (:type %)) %) @(:events events))
          snapshot (js->clj (.snapshot ^js agency) :keywordize-keys true)]
      (is partial)
      (is final)
      (is (< (:sequence partial) (:sequence final)))
      (is (= "conversation" (:targetAgency final)))
      (is (= "hello world" (:text final)))
      (is (= 1 (:partialCount snapshot)))
      (is (= 1 (:finalCount snapshot))))
    ((:unsubscribe events))
    (.dispose ^js agency)))

(deftest provider-error-emits-bounded-retry-request
  (let [agency (transcription/create-transcription-agency #js {:maxRetries 1
                                                              :retryDelayMs 25})
        events (collect-events agency)]
    (.start ^js agency)
    (.dispatch ^js agency #js {:type "providerError" :message "network"})
    (.dispatch ^js agency #js {:type "providerError" :message "network-again"})
    (let [retry-requests (filter #(and (= "transcription.requestProvider" (:type %))
                                       (= "retry" (:action %)))
                                 @(:events events))
          snapshot (js->clj (.snapshot ^js agency) :keywordize-keys true)]
      (is (= 1 (count retry-requests)))
      (is (= 1 (:retryCount snapshot)))
      (is (= "error" (:status snapshot))))
    ((:unsubscribe events))
    (.dispose ^js agency)))

(deftest tts-status-controls-agent-speech-filtering-and-interruption-facts
  (let [agency (transcription/create-transcription-agency nil)
        events (collect-events agency)]
    (.start ^js agency)
    (.dispatch ^js agency #js {:type "ttsSpeechStarted"})
    (.dispatch ^js agency #js {:type "providerFinal"
                               :text "agent echo"
                               :source "tts"})
    (.dispatch ^js agency #js {:type "providerFinal"
                               :text "user interruption"
                               :source "microphone"})
    (let [snapshot (js->clj (.snapshot ^js agency) :keywordize-keys true)]
      (is (:agentSpeaking snapshot))
      (is (= 1 (:ignoredCount snapshot)))
      (is (= 1 (:interruptionCount snapshot)))
      (is (some #(and (= "transcription.ignored" (:type %))
                      (= "agent-speech" (:reason %)))
                @(:events events)))
      (is (some #(and (= "transcription.interruption" (:type %))
                      (= "user interruption" (:text %)))
                @(:events events)))
      (is (< (.indexOf (mapv :type @(:events events)) "transcription.interruption")
             (.indexOf (mapv :type @(:events events)) "transcription.final"))))
    (.dispatch ^js agency #js {:type "ttsSpeechStopped"})
    (let [snapshot (js->clj (.snapshot ^js agency) :keywordize-keys true)]
      (is (false? (:agentSpeaking snapshot))))
    ((:unsubscribe events))
    (.dispose ^js agency)))

(deftest disposed-transcription-stops-emitting
  (let [agency (transcription/create-transcription-agency nil)
        events (collect-events agency)]
    (.dispose ^js agency)
    (.start ^js agency)
    (.dispatch ^js agency #js {:type "providerFinal" :text "ignored"})
    (is (empty? @(:events events)))))
