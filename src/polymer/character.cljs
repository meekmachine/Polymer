(ns polymer.character
  (:require [polymer.blink.agency :as blink]
            [polymer.stream :as stream]))

(defn create-character-agencies [config]
  (let [input (if config (js->clj config :keywordize-keys true) {})
        status-stream (stream/create-stream)
        command-stream (stream/create-stream)
        emit-status (:emit status-stream)
        emit-command (:emit command-stream)
        blink-agency (blink/create-blink-agency (clj->js (:blink input)))
        unsubscribers (atom [])
        disposed? (atom false)]
    (letfn [(track! [unsubscribe]
              (swap! unsubscribers conj unsubscribe))

            (dispatch! [message]
              (when-not @disposed?
                (let [payload (js->clj message :keywordize-keys true)]
                  (case (:agency payload)
                    "blink" (.dispatch ^js blink-agency (clj->js (:command payload)))
                    (emit-status {:type "error"
                                  :agency (or (:agency payload) "unknown")
                                  :message "Unknown Polymer agency"})))))

            (snapshot! []
              (clj->js {:blink (js->clj (.snapshot ^js blink-agency) :keywordize-keys true)}))]
      (track! (.subscribeStatus ^js blink-agency
                                #(emit-status (js->clj % :keywordize-keys true))))
      (track! (.subscribeCommands ^js blink-agency
                                  #(emit-command (js->clj % :keywordize-keys true))))
      #js {:dispatch dispatch!
           :snapshot snapshot!
           :agency (fn [name]
                     (case name
                       "blink" blink-agency
                       nil))
           :subscribe (fn [listener] ((:subscribe status-stream) listener))
           :subscribeStatus (fn [listener] ((:subscribe status-stream) listener))
           :subscribeCommands (fn [listener] ((:subscribe command-stream) listener))
           :dispose (fn []
                      (when-not @disposed?
                        (reset! disposed? true)
                        (doseq [unsubscribe @unsubscribers]
                          (unsubscribe))
                        (reset! unsubscribers [])
                        (.dispose ^js blink-agency)
                        ((:dispose status-stream))
                        ((:dispose command-stream))))})))
