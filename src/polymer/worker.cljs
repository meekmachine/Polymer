(ns polymer.worker
  (:require [polymer.core :as polymer]))

(defonce systems (atom {}))

(defn post! [message]
  (.postMessage js/self (clj->js message)))

(defn dispose-system! [id]
  (when-let [system (get @systems id)]
    (.dispose ^js system)
    (swap! systems dissoc id)))

(defn create-system! [id config]
  (dispose-system! id)
  (let [system (polymer/createCharacterAgencies (clj->js config))]
    (.subscribeStatus ^js system #(post! {:id id :stream "status" :event (js->clj % :keywordize-keys true)}))
    (.subscribeCommands ^js system #(post! {:id id :stream "commands" :event (js->clj % :keywordize-keys true)}))
    (swap! systems assoc id system)
    (post! {:id id :stream "status" :event {:type "ready" :agency "character"}})))

(defn dispatch! [id message]
  (if-let [system (get @systems id)]
    (.dispatch ^js system (clj->js message))
    (post! {:id id
            :stream "status"
            :event {:type "error"
                    :agency "character"
                    :message "Unknown Polymer agency system id"}})))

(defn handle-message [message]
  (let [payload (js->clj (.-data message) :keywordize-keys true)
        id (or (:id payload) "character")]
    (case (:type payload)
      "createCharacterAgencies" (create-system! id (:config payload))
      "createBlinkAgency" (create-system! id {:blink (:config payload)})
      "dispatch" (dispatch! id (:message payload))
      "dispose" (dispose-system! id)
      (post! {:id id
              :stream "status"
              :event {:type "error"
                      :agency "character"
                      :message (str "Unknown worker message: " (:type payload))}}))))

(defn init []
  (.addEventListener js/self "message" handle-message))
