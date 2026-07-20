(ns polymer.conversation.domain
  (:require [clojure.string :as str]))

;; Pure Conversation transforms live here so command normalization is shared by
;; state, planner, and the JS-facing agency boundary.

(defn data-map
  [value]
  (cond
    (map? value) value
    value (js->clj value :keywordize-keys true)
    :else {}))

(defn clean-text
  [value]
  (let [text (str/trim (or value ""))]
    (when (pos? (count text))
      text)))

(defn text-or
  [value fallback]
  (or (clean-text value) fallback))

(defn user-text
  [command]
  (clean-text (or (:text command)
                  (:transcript command)
                  (:utterance command))))

(defn agent-text
  [command]
  (clean-text (or (:agentText command)
                  (:responseText command)
                  (:text command)
                  (:utterance command))))
