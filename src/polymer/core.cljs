(ns polymer.core
  (:require [polymer.blink.agency :as blink]
            [polymer.character :as character]))

;; Public JavaScript entry points.
;;
;; Polymer is authored in CLJS, but LoomLarge imports the compiled package from
;; JavaScript/TypeScript. Keep the exported surface small and character-centric:
;; direct agency creation is useful for tests and development, while production
;; hosts should use createCharacterAgencies so all streams share one boundary.

(defn createBlinkAgency
  "Create the Blink agency directly. Prefer createCharacterAgencies in hosts
  that need a stable multi-agency integration point."
  ([] (blink/create-blink-agency nil))
  ([config] (blink/create-blink-agency config)))

(defn createCharacterAgencies
  "Create the per-character Polymer agency system."
  ([] (character/create-character-agencies nil))
  ([config] (character/create-character-agencies config)))
