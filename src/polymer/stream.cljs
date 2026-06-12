(ns polymer.stream)

(defn create-stream
  "Synchronous boundary stream.
  Stream delivery is intentionally tiny: Polymer emits plain data, and the host
  decides which command events become side effects."
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
