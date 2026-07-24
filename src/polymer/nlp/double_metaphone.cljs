(ns polymer.nlp.double-metaphone
  (:require [clojure.string :as str]
            [clj-fuzzy.double-metaphone :as dm]))

;; Double Metaphone via clj-fuzzy (native CLJS phonetics).
;;
;; clj-fuzzy's public `process` truncates codes to 4 chars (classic metaphone
;; key length). LipSync wants the full code as a phoneme skeleton, so we run
;; the same `lookup` loop without that cap.

(defn- prep-string [string]
  (str (str/upper-case (str (or string ""))) "     "))

(defn- start-position [pstring]
  (if (re-find #"^(?:GN|KN|PN|WR|PS)" (subs pstring 0 (min 2 (count pstring))))
    1
    0))

(defn- symbols->string [symbols]
  (->> symbols
       (map name)
       (apply str)))

(defn- conj-if-some [coll value]
  (if (nil? value) coll (conj coll value)))

(defn- process-full
  "clj-fuzzy Double Metaphone without the 4-character truncation."
  [string]
  (let [pstring (prep-string string)
        length (count (str (or string "")))
        lastp (dec length)]
    (loop [pos (start-position pstring)
           primary []
           secondary []]
      (if (> pos length)
        [(symbols->string primary) (symbols->string secondary)]
        (let [[newp news offset] (dm/lookup pstring pos length lastp)]
          (recur (+ offset pos)
                 (conj-if-some primary newp)
                 (conj-if-some secondary news)))))))

(defn double-metaphone
  "Return {:primary :secondary} Double Metaphone codes for word."
  [word]
  (let [[primary secondary] (process-full word)]
    {:primary (or primary "")
     :secondary (or secondary "")}))

(defn primary-code [word]
  (:primary (double-metaphone word)))

(defn secondary-code [word]
  (:secondary (double-metaphone word)))
