(ns polymer.camera-context.state
  (:require [polymer.camera-context.domain :as domain]))

;; Camera Context state is the local ledger of stable runtime facts. The agency
;; may observe many camera updates, but only scheduler-approved facts become
;; published stream data for peer agencies.

(def default-config
  {:coalesceMs 16
   :staleAfterMs 1000
   :epsilon domain/default-epsilon
   :yawWeight domain/default-yaw-weight
   :pitchWeight domain/default-pitch-weight})

(defn normalize-config
  [config]
  (let [input (merge default-config (domain/data-map config))]
    {:coalesceMs (int (domain/clamp 0 1000
                                    (domain/number-or (:coalesceMs input)
                                                      (:coalesceMs default-config))))
     :staleAfterMs (int (domain/clamp 0 60000
                                      (domain/number-or (:staleAfterMs input)
                                                        (:staleAfterMs default-config))))
     :epsilon (domain/clamp 1.0e-9 1
                             (domain/number-or (:epsilon input)
                                               (:epsilon default-config)))
     :yawWeight (domain/clamp 0 1
                              (domain/number-or (:yawWeight input)
                                                (:yawWeight default-config)))
     :pitchWeight (domain/clamp 0 1
                                (domain/number-or (:pitchWeight input)
                                                  (:pitchWeight default-config)))}))

(defn default-state
  [config]
  {:agency "cameraContext"
   :status "idle"
   :cameraPosition nil
   :targetPosition nil
   :modelQuaternion domain/identity-quaternion
   :relativeOffset domain/zero-offset
   :lastUpdatedAt nil
   :lastPublishedAt nil
   :lastFact nil
   :lastPlan nil
   :lastInvalidation nil
   :stale false
   :invalidated false
   :updateCount 0
   :publishedCount 0
   :config (normalize-config config)})

(defn config->state
  [config]
  (default-state config))

(defn visible-state
  [state]
  (clj->js state))

(defn configure
  [state config]
  (assoc state :config (normalize-config (merge (:config state)
                                                (domain/data-map config)))))

(defn reset-state
  [state]
  ;; Reset clears camera facts and counters while preserving the normalized
  ;; runtime cadence supplied when this agency was created or configured.
  (default-state (:config state)))

(defn record-plan
  [state plan]
  (assoc state :lastPlan plan))

(defn record-camera-update
  [state fact]
  (-> state
      (assoc :status "pending"
             :cameraPosition (:cameraPosition fact)
             :targetPosition (:targetPosition fact)
             :modelQuaternion (:modelQuaternion fact)
             :relativeOffset (:relativeOffset fact)
             :lastUpdatedAt (:observedAt fact)
             :stale false
             :invalidated false)
      (update :updateCount inc)))

(defn record-published
  [state fact published-at]
  (-> state
      (assoc :status "fresh"
             :lastFact (assoc fact :publishedAt published-at)
             :lastPublishedAt published-at
             :stale false
             :invalidated false)
      (update :publishedCount inc)))

(defn stale-fact
  [state invalidated-at reason]
  {:kind "camera.stale"
   :agency "cameraContext"
   :status "stale"
   :reason reason
   :invalidatedAt invalidated-at
   :lastPublishedAt (:lastPublishedAt state)
   :lastObservedAt (:lastUpdatedAt state)
   :lastRelativeOffset (:relativeOffset state)})

(defn can-invalidate?
  [state]
  (and (:lastPublishedAt state) (not (:stale state))))

(defn record-stale
  [state invalidated-at reason]
  (let [fact (stale-fact state invalidated-at reason)]
    (assoc state
           :status "stale"
           :stale true
           :invalidated true
           :lastInvalidation fact)))
