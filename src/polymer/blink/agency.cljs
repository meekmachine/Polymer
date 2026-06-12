(ns polymer.blink.agency
  (:require [polymer.blink.scheduler :as scheduler]
            [polymer.blink.state :as state]
            [polymer.stream :as stream]))

(defn js-command [type value]
  #js {:type type :value value})

(defn create-blink-agency [config]
  (let [status-stream (stream/create-stream)
        command-stream (stream/create-stream)
        state-atom (atom (state/config->state config))
        disposed? (atom false)
        emit-status (:emit status-stream)
        emit-command (:emit command-stream)
        scheduler-atom (atom nil)]
    (letfn [(publish-state! []
              (emit-status {:type "state"
                            :agency "blink"
                            :state @state-atom}))

            (record-plan! [plan]
              (let [now-ms (or (:created-at plan) (.now js/Date))]
                (swap! state-atom state/record-plan plan now-ms)
                (publish-state!)))

            (refresh-auto! []
              (when-let [agency-scheduler @scheduler-atom]
                ((:refresh-auto agency-scheduler))))

            (dispatch! [command]
              (when-not @disposed?
                (let [payload (js->clj command :keywordize-keys true)
                      type (:type payload)]
                  (case type
                    "triggerBlink"
                    (when-let [agency-scheduler @scheduler-atom]
                      ((:trigger agency-scheduler) (:options payload)))

                    ("enable" "disable" "setFrequency" "setDuration" "setIntensity"
                     "setRandomness" "setLeftEyeIntensity" "setRightEyeIntensity"
                     "setBurstEnabled" "setBurstFrequency" "setBurstCount" "setBurstGap"
                     "configure" "reset")
                    (do
                      (swap! state-atom state/apply-command payload)
                      (publish-state!)
                      (refresh-auto!))

                    (emit-status {:type "error"
                                  :agency "blink"
                                  :message (str "Unknown Blink command: " type)})))))]
      (let [agency-scheduler (scheduler/create-scheduler
                              {:state-atom state-atom
                               :emit-status emit-status
                               :emit-command emit-command
                               :record-plan! record-plan!})]
        (reset! scheduler-atom agency-scheduler)
        (publish-state!)
        #js {:dispatch dispatch!
             :snapshot (fn [] (state/visible-state @state-atom))
             :subscribe (fn [listener] ((:subscribe status-stream) listener))
             :subscribeStatus (fn [listener] ((:subscribe status-stream) listener))
             :subscribeCommands (fn [listener] ((:subscribe command-stream) listener))
             :enable (fn [] (dispatch! (js-command "enable" true)))
             :disable (fn [] (dispatch! (js-command "disable" false)))
             :triggerBlink (fn
                             ([] (dispatch! #js {:type "triggerBlink"}))
                             ([options] (dispatch! #js {:type "triggerBlink" :options options})))
             :setFrequency (fn [value] (dispatch! (js-command "setFrequency" value)))
             :setDuration (fn [value] (dispatch! (js-command "setDuration" value)))
             :setIntensity (fn [value] (dispatch! (js-command "setIntensity" value)))
             :setRandomness (fn [value] (dispatch! (js-command "setRandomness" value)))
             :setLeftEyeIntensity (fn [value] (dispatch! (js-command "setLeftEyeIntensity" value)))
             :setRightEyeIntensity (fn [value] (dispatch! (js-command "setRightEyeIntensity" value)))
             :setBurstEnabled (fn [value] (dispatch! (js-command "setBurstEnabled" value)))
             :setBurstFrequency (fn [value] (dispatch! (js-command "setBurstFrequency" value)))
             :setBurstCount (fn [value] (dispatch! (js-command "setBurstCount" value)))
             :setBurstGap (fn [value] (dispatch! (js-command "setBurstGap" value)))
             :reset (fn [] (dispatch! #js {:type "reset"}))
             :dispose (fn []
                        (when-not @disposed?
                          (reset! disposed? true)
                          ((:dispose agency-scheduler))
                          ((:dispose status-stream))
                          ((:dispose command-stream))))}))))
