(ns polymer.blink.agency
  (:require [polymer.blink.scheduler :as scheduler]
            [polymer.blink.state :as state]
            [polymer.stream :as stream]))

(defn command-map [type value]
  #js {:type type :value value})

(defn create-blink-agency [config]
  (let [status-stream (stream/create-stream)
        command-stream (stream/create-stream)
        state-atom (atom (state/js->state config))
        emit-status (:emit status-stream)
        emit-command (:emit command-stream)
        disposed? (atom false)
        scheduler (scheduler/create-scheduler
                   {:state-atom state-atom
                    :emit-status emit-status
                    :emit-command emit-command})]
    (letfn [(publish-state! []
              (emit-status {:type "state"
                            :agency "blink"
                            :state (state/visible-state @state-atom)}))

            (dispatch! [command]
              (when-not @disposed?
                (let [payload (js->clj command :keywordize-keys true)
                      type (:type payload)]
                  (case type
                    "triggerBlink"
                    ((:trigger scheduler) (:options payload))

                    (do
                      (reset! state-atom (state/normalize-state (state/apply-command @state-atom command)))
                      (publish-state!)
                      (when (#{"enable" "disable" "setFrequency" "setRandomness" "configure" "reset"} type)
                        ((:reschedule scheduler))))))))]
      (publish-state!)
      #js {:dispatch dispatch!

           :snapshot
           (fn []
             (clj->js (state/visible-state @state-atom)))

           :subscribe
           (fn [listener]
             ((:subscribe status-stream) listener))

           :subscribeStatus
           (fn [listener]
             ((:subscribe status-stream) listener))

           :subscribeCommands
           (fn [listener]
             ((:subscribe command-stream) listener))

           :enable
           (fn [] (dispatch! (command-map "enable" true)))

           :disable
           (fn [] (dispatch! (command-map "disable" false)))

           :triggerBlink
           (fn
             ([] (dispatch! #js {:type "triggerBlink"})
             )
             ([options] (dispatch! #js {:type "triggerBlink" :options options})))

           :setFrequency
           (fn [value] (dispatch! (command-map "setFrequency" value)))

           :setDuration
           (fn [value] (dispatch! (command-map "setDuration" value)))

           :setIntensity
           (fn [value] (dispatch! (command-map "setIntensity" value)))

           :setRandomness
           (fn [value] (dispatch! (command-map "setRandomness" value)))

           :setBurstEnabled
           (fn [value] (dispatch! (command-map "setBurstEnabled" value)))

           :setBurstChance
           (fn [value] (dispatch! (command-map "setBurstChance" value)))

           :setBurstCount
           (fn [value] (dispatch! (command-map "setBurstCount" value)))

           :setBurstGap
           (fn [value] (dispatch! (command-map "setBurstGap" value)))

           :reset
           (fn [] (dispatch! (command-map "reset" true)))

           :dispose
           (fn []
             (reset! disposed? true)
             ((:dispose scheduler))
             ((:dispose status-stream))
             ((:dispose command-stream)))})))
