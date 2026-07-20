(ns polymer.transcription.domain
  (:require [clojure.string :as str]))

;; Pure Transcription transforms live here. Provider/browser work stays outside
;; this namespace; these helpers only normalize command data and transcript text.

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

(defn transcript-text
  [command]
  (clean-text (or (:text command) (:transcript command))))

(defn agent-source?
  [command]
  (contains? #{"agent" "tts" "speaker" "system"}
             (str/lower-case (or (:source command) (:speaker command) ""))))
