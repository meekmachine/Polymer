(ns polymer.character-test
  (:require [cljs.test :refer [deftest is]]
            [polymer.core :as polymer]))

(defn collect-events
  [target]
  (let [events (atom [])]
    {:events events
     :unsubscribe (.subscribeEvents ^js target
                                    #(swap! events conj (js->clj % :keywordize-keys true)))}))

(defn make-runtime
  [calls]
  #js {:playSnippet
       (fn [name curves options]
         (swap! calls conj {:method "playSnippet"
                            :name name
                            :curves (js->clj curves :keywordize-keys true)
                            :options (js->clj options :keywordize-keys true)})
         #js {:clipName name
              :stop (fn [] true)})
       :cleanupSnippet
       (fn [name]
         (swap! calls conj {:method "cleanupSnippet" :name name})
         true)})

(deftest character-network-exposes-all-first-slice-agencies
  (let [system (polymer/createCharacterAgencies nil)
        snapshot (js->clj (.snapshot ^js system) :keywordize-keys true)]
    (is (.agency ^js system "conversation"))
    (is (.agency ^js system "transcription"))
    (is (.agency ^js system "hair"))
    (is (.agency ^js system "cameraContext"))
    (is (= "conversation" (get-in snapshot [:conversation :agency])))
    (is (= "transcription" (get-in snapshot [:transcription :agency])))
    (is (= "hair" (get-in snapshot [:hair :agency])))
    (is (= "cameraContext" (get-in snapshot [:cameraContext :agency])))
    (.dispose ^js system)))

(deftest character-network-routes-transcription-to-conversation-and-agent-response-to-tts
  (let [calls (atom [])
        system (polymer/createCharacterAgencies
                #js {:animation #js {:runtime (make-runtime calls)}
                     :tts #js {:engine "webSpeech"}})
        events (collect-events system)]
    (.dispatch ^js system
               #js {:agency "transcription"
                    :command #js {:type "providerFinal"
                                  :text "hello character"
                                  :confidence 1}})
    (is (some #(and (= "transcription.final" (:type %))
                    (= "hello character" (:text %)))
              @(:events events)))
    (is (some #(and (= "conversation.requestResponse" (:type %))
                    (= "hello character" (:text %)))
              @(:events events)))
    (.dispatch ^js system
               #js {:agency "conversation"
                    :command #js {:type "responseReady"
                                  :text "hello back"}})
    (is (some #(and (= "tts.requestSpeak" (:type %))
                    (= "hello back" (:text %)))
              @(:events events)))
    ((:unsubscribe events))
    (.dispose ^js system)))

(deftest character-network-routes-camera-facts-to-gaze
  (let [system (polymer/createCharacterAgencies
                #js {:cameraContext #js {:coalesceMs 0}
                     :gaze #js {:enabled true}})
        events (collect-events system)]
    (.dispatch ^js system
               #js {:agency "cameraContext"
                    :command #js {:type "updateCamera"
                                  :cameraPosition #js {:x 1 :y 0 :z 2}
                                  :targetPosition #js {:x 0 :y 0 :z 0}
                                  :modelQuaternion #js {:x 0 :y 0 :z 0 :w 1}}})
    (is (some #(= "camera.fact" (:type %)) @(:events events)))
    (is (some #(= "gaze.targetPlanned" (:type %)) @(:events events)))
    ((:unsubscribe events))
    (.dispose ^js system)))
