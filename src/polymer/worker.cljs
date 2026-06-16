(ns polymer.worker
  (:require [polymer.core :as polymer]))

;; Worker support is a protocol smoke test for the architecture.
;;
;; LoomLarge does not use this worker in the first integration, but keeping it
;; wired to the same input/events/effects streams proves the package can move
;; agency-local timers off the main thread later without changing the agency
;; message shape.

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
    ;; Mirror every character-level output stream through worker messages.
    ;; The host can interpret effects in the main thread while the agency timing
    ;; and planning stay isolated in the worker.
    (.subscribeEvents ^js system #(post! {:id id :stream "events" :event (js->clj % :keywordize-keys true)}))
    (.subscribeEffects ^js system #(post! {:id id :stream "effects" :event (js->clj % :keywordize-keys true)}))
    (swap! systems assoc id system)
    (post! {:id id :stream "events" :event {:type "ready" :agency "character"}})))

(defn dispatch! [id message]
  (if-let [system (get @systems id)]
    (.dispatch ^js system (clj->js message))
    (post! {:id id
            :stream "events"
            :event {:type "error"
                    :agency "character"
                    :message "Unknown Polymer agency system id"}})))

(defn handle-message [message]
  (let [payload (js->clj (.-data message) :keywordize-keys true)
        id (or (:id payload) "character")]
    (case (:type payload)
      "createCharacterAgencies" (create-system! id (:config payload))
      "createBlinkAgency" (create-system! id {:blink (:config payload)})
      "createAnimationAgency" (create-system! id {:animation (:config payload)})
      "dispatch" (dispatch! id (:message payload))
      "dispose" (dispose-system! id)
      (post! {:id id
              :stream "events"
              :event {:type "error"
                      :agency "character"
                      :message (str "Unknown worker message: " (:type payload))}}))))

(defn init []
  (.addEventListener js/self "message" handle-message))
