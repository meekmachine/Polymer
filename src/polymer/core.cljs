(ns polymer.core
  (:require [polymer.animation.agency :as animation]
            [polymer.blink.agency :as blink]
            [polymer.character :as character]))

;; Public JavaScript entry points.
;;
;; Polymer is authored in CLJS, but LoomLarge imports the compiled package from
;; JavaScript/TypeScript. The exports stay agency-oriented: LoomLarge can create
;; a character agency network, but Polymer owns the cross-agency routing and
;; Animation owns the Loom3/Embody runtime calls.

(defn createBlinkAgency
  "Create the Blink agency directly. Use createCharacterAgencies when Blink
  should route animation intent to Polymer Animation."
  ([] (blink/create-blink-agency nil))
  ([config] (blink/create-blink-agency config)))

(defn createAnimationAgency
  "Create the Animation agency directly. Pass an Embody runtime or Loom3 engine
  in config when the agency should execute snippets."
  ([] (animation/create-animation-agency nil))
  ([config] (animation/create-animation-agency config)))

(defn createCharacterAgencies
  "Create the per-character Polymer agency network."
  ([] (character/create-character-agencies nil))
  ([config] (character/create-character-agencies config)))
