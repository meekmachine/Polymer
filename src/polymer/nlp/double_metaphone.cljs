(ns polymer.nlp.double-metaphone
  (:require ["./double_metaphone_algo.js" :refer [doubleMetaphone]]))

;; Polymer-owned Double Metaphone (Philips).
;; Faithful in-repo copy of the MIT double-metaphone reference algorithm
;; (Titus Wormer / Lawrence Philips). See DOUBLE_METAPHONE_LICENSE.txt.
;;
;; Agencies consume only this CLJS data API. LipSync/Emphatic never call
;; ad-hoc grapheme letter tables for G2P.

(defn double-metaphone
  "Return {:primary :secondary} Double Metaphone codes for word."
  [word]
  (let [result (doubleMetaphone (str (or word "")))]
    {:primary (or (aget result 0) "")
     :secondary (or (aget result 1) "")}))

(defn primary-code [word]
  (:primary (double-metaphone word)))

(defn secondary-code [word]
  (:secondary (double-metaphone word)))
