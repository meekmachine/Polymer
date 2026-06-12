(ns polymer.worker
  (:require [polymer.core :as polymer]))

(defonce agencies (atom {}))

(defn post! [message]
  (.postMessage js/self (clj->js message)))

(defn dispose-agency! [id]
  (when-let [agency (get @agencies id)]
    (.dispose ^js agency)
    (swap! agencies dissoc id)))

(defn create-agency! [id config]
  (dispose-agency! id)
  (let [agency (polymer/createBlinkAgency (clj->js config))]
    (.subscribeStatus ^js agency #(post! {:id id :stream "status" :event (js->clj % :keywordize-keys true)}))
    (.subscribeCommands ^js agency #(post! {:id id :stream "commands" :event (js->clj % :keywordize-keys true)}))
    (swap! agencies assoc id agency)
    (post! {:id id :stream "status" :event {:type "ready" :agency "blink"}})))

(defn dispatch! [id command]
  (if-let [agency (get @agencies id)]
    (.dispatch ^js agency (clj->js command))
    (post! {:id id
            :stream "status"
            :event {:type "error"
                    :agency "blink"
                    :message "Unknown Blink agency id"}})))

(defn handle-message [message]
  (let [payload (js->clj (.-data message) :keywordize-keys true)
        id (or (:id payload) "blink")]
    (case (:type payload)
      "createBlinkAgency" (create-agency! id (:config payload))
      "dispatch" (dispatch! id (:command payload))
      "dispose" (dispose-agency! id)
      (post! {:id id
              :stream "status"
              :event {:type "error"
                      :agency "blink"
                      :message "Unknown worker message type"}}))))

(defn init []
  (set! (.-onmessage js/self) handle-message))
