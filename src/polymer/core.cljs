(ns polymer.core
  (:require [polymer.blink.agency :as blink]))

(defn createBlinkAgency
  "Create a Blink agency. JS callers receive a plain object API."
  ([] (blink/create-blink-agency nil))
  ([config] (blink/create-blink-agency config)))
