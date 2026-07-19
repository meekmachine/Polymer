(ns polymer.animation.agency
  (:require [polymer.animation.planner :as planner]
            [polymer.animation.runtime :as runtime]
            [polymer.animation.scheduler :as scheduler]
            [polymer.animation.state :as state]
            [polymer.stream :as stream]))

;; Animation is Polymer's runtime-owning agency. Peer agencies send animation
;; requests as plain data; this agency plans those requests, schedules ordered
;; runtime work, and keeps the external animation handle lifecycle private.

(defn debug-flag-enabled? [name]
  (try
    (let [search (when (exists? js/window)
                   (.. js/window -location -search))
          params (when search (js/URLSearchParams. search))
          value (when params (.get params name))]
      (or (= value "1") (= value "true")))
    (catch :default _ false)))

(defn animation-debug-enabled? []
  (or (debug-flag-enabled? "polymerVocalDebug")
      (debug-flag-enabled? "polymerLipSyncDebug")))

(defn debug-log! [label payload]
  (when (animation-debug-enabled?)
    (.info js/console (str label " " (.stringify js/JSON (clj->js payload))))))

(defn create-animation-agency [config]
  (let [runtime (runtime/config->runtime config)
        input-stream (stream/create-stream)
        event-stream (stream/create-stream)
        effect-stream (stream/create-stream)
        state-atom (atom state/default-state)
        disposed? (atom false)
        emit-input (:emit input-stream)
        emit-event (:emit event-stream)
        animation-scheduler (scheduler/create-scheduler
                             {:runtime runtime
                              :state-atom state-atom
                              :emit-event emit-event
                              :disposed? disposed?
                              :debug-log! debug-log!})]
    (letfn [(dispatch! [command]
              (when-not @disposed?
                (let [payload (js->clj command :keywordize-keys true)
                      requested-at (state/now-ms)]
                  (emit-input {:type "command"
                               :agency "animation"
                               :command payload})
                  ((:enqueue-actions! animation-scheduler)
                   (planner/plan-command @state-atom payload requested-at)))))]
      #js {:dispatch dispatch!
           :snapshot (fn [] (state/visible-state @state-atom))
           :input (stream/writable-port input-stream dispatch!)
           :events (stream/readable-port event-stream)
           ;; Kept as an empty compatibility stream so older consumers can
           ;; unsubscribe safely. Animation side effects are internal runtime
           ;; calls after planner and scheduler acceptance.
           :effects (stream/readable-port effect-stream)
           :subscribeInput (fn [listener] ((:subscribe input-stream) listener))
           :subscribeEvents (fn [listener] ((:subscribe event-stream) listener))
           :subscribeEffects (fn [listener] ((:subscribe effect-stream) listener))
           :subscribe (fn [listener] ((:subscribe event-stream) listener))
           :subscribeStatus (fn [listener] ((:subscribe event-stream) listener))
           :subscribeCommands (fn [listener] ((:subscribe effect-stream) listener))
           :scheduleSnippet (fn [snippet options]
                              (dispatch! #js {:type "scheduleSnippet"
                                              :sourceAgency "direct"
                                              :snippet snippet
                                              :options options}))
           :removeSnippet (fn [name]
                            (dispatch! #js {:type "removeSnippet"
                                            :sourceAgency "direct"
                                            :name name}))
           :seekSnippet (fn [name offset-sec]
                          (dispatch! #js {:type "seekSnippet"
                                          :sourceAgency "direct"
                                          :name name
                                          :offsetSec offset-sec}))
           :updateSnippet (fn [name params]
                            (dispatch! #js {:type "updateSnippet"
                                            :sourceAgency "direct"
                                            :name name
                                            :params params}))
           :dispose (fn []
                      (when-not @disposed?
                        (reset! disposed? true)
                        ((:dispose! animation-scheduler))
                        ((:dispose input-stream))
                        ((:dispose event-stream))
                        ((:dispose effect-stream))))})))
