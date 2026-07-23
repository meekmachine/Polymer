(ns polymer.hair.planner
  (:require [polymer.hair.domain :as domain]))

;; Hair planning converts profile/runtime facts into local actions. The result
;; is still just data; the scheduler decides ordering and coalescing before any
;; runtime effector sees a request.

(def supported-command-types
  #{"configure"
    "registerObjects"
    "motionFact"
    "environmentFact"
    "reset"
    "setHairColor"
    "setEyebrowColor"
    "setOutline"
    "setBaseColor"
    "setHairBaseColor"
    "configurePhysics"
    "updatePhysicsConfig"
    "setPhysicsEnabled"})

(defn failure-step
  [command]
  (when-not (contains? supported-command-types (:type command))
    {:op "fail"
     :reason "unsupported-command"
     :commandType (:type command)}))

(defn command-steps
  [command]
  (if-let [failure (failure-step command)]
    [failure]
    (case (:type command)
      "configure" [{:op "apply-config"}
                   {:op "request-apply-state"}]
      ;; Register object refs only. Applying materials here would paint default
      ;; colors whenever a host UI mounts HairService and registers meshes.
      "registerObjects" [{:op "apply-config"
                          :config {:objects (:objects command)}}]
      "setHairColor" [{:op "apply-config"
                       :config {:hairColor (or (:color command)
                                               (:value command)
                                               (:hairColor command))}}
                      {:op "request-apply-state"}]
      ("setBaseColor" "setHairBaseColor") [{:op "apply-config"
                                            :config {:hairColor (or (:color command)
                                                                    (:value command)
                                                                    (:baseColor command)
                                                                    (:base-color command))}}
                                           {:op "request-apply-state"}]
      "setEyebrowColor" [{:op "apply-config"
                          :config {:eyebrowColor (or (:color command)
                                                     (:value command)
                                                     (:eyebrowColor command))}}
                         {:op "request-apply-state"}]
      "setOutline" [{:op "apply-config"
                     :config {:showOutline (:show command)
                              :outlineColor (:color command)
                              :outlineOpacity (:opacity command)}}
                    {:op "request-apply-state"}]
      ("configurePhysics" "updatePhysicsConfig") [{:op "apply-config"
                                                   :config {:physics (or (:physics command)
                                                                         (:config command)
                                                                         (:value command))}}
                                                  {:op "request-apply-state"}]
      "setPhysicsEnabled" [{:op "apply-config"
                            :config {:physics {:enabled (:enabled command)}}}
                           {:op "request-apply-state"}]
      ("motionFact" "environmentFact") [{:op "record-motion"
                                         :motion (domain/data-map (or (:motion command) (:facts command) command))}
                                        {:op "coalesce-motion"}]
      "reset" [{:op "reset-state"}
               {:op "request-reset"}])))

(defn plan-command
  [command state now-ms]
  (let [steps (command-steps command)]
    {:agency "hair"
     :commandType (:type command)
     :createdAt now-ms
     :ok (not= "fail" (:op (first steps)))
     :steps steps}))
