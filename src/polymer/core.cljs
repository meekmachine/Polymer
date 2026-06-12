(ns polymer.core
  (:require [polymer.blink.agency :as blink]
            [polymer.character :as character]))

(defn createBlinkAgency
  "Create the Blink agency directly. Prefer createCharacterAgencies in hosts
  that need a stable multi-agency integration point."
  ([] (blink/create-blink-agency nil))
  ([config] (blink/create-blink-agency config)))

(defn createCharacterAgencies
  "Create the per-character Polymer agency system."
  ([] (character/create-character-agencies nil))
  ([config] (character/create-character-agencies config)))
