(ns polymer.stream)

(defn create-stream
  "Small synchronous stream used at the agency boundary.
  It keeps Polymer's output as data and leaves side effects to the host."
  []
  (let [listeners (atom #{})]
    {:emit
     (fn [event]
       (let [payload (clj->js event)]
         (doseq [listener @listeners]
           (listener payload))))

     :subscribe
     (fn [listener]
       (swap! listeners conj listener)
       (fn []
         (swap! listeners disj listener)))

     :dispose
     (fn []
       (reset! listeners #{}))}))
