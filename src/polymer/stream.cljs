(ns polymer.stream)

;; Polymer keeps its stream primitive deliberately small.
;;
;; The important architectural point is not this particular implementation.
;; The important point is the boundary shape: agencies publish plain immutable
;; data and the host decides which outputs become real side effects. That keeps
;; CLJS agency logic portable across LoomLarge, workers, tests, and later
;; all-Polymer hosts.

(defn create-stream
  "Create a synchronous in-process stream.

  Listeners receive JavaScript objects because this is the public package
  boundary. Inside Polymer we keep using Clojure maps so agency code stays
  simple and data-oriented."
  []
  (let [listeners (atom #{})]
    {:emit
     (fn [event]
       ;; Emitting is the only mutation this helper performs. It does not know
       ;; whether an event is state, a domain event, or a requested host effect.
       (let [payload (clj->js event)]
         (doseq [listener @listeners]
           (listener payload))))

     :subscribe
     (fn [listener]
       ;; Subscriptions return an unsubscriber so React, workers, and tests can
       ;; tear down character instances without leaking callbacks.
       (swap! listeners conj listener)
       (fn []
         (swap! listeners disj listener)))

     :dispose
     (fn []
       ;; Disposal is intentionally idempotent at the stream level: clearing an
       ;; empty listener set is harmless.
       (reset! listeners #{}))}))

(defn readable-port
  "Expose a JS-facing read-only stream port."
  [stream]
  #js {:subscribe (fn [listener] ((:subscribe stream) listener))})

(defn writable-port
  "Expose a JS-facing writable stream port.

  The write function is supplied by the owning agency/system because writing to
  input usually means validating and routing a command, not blindly broadcasting
  data."
  [stream write!]
  #js {:write write!
       :subscribe (fn [listener] ((:subscribe stream) listener))})

(defn subscribe-many
  "Subscribe one listener to several streams and return one unsubscriber."
  [streams listener]
  (let [unsubscribers (mapv #((:subscribe %) listener) streams)]
    (fn []
      (doseq [unsubscribe unsubscribers]
        (unsubscribe)))))
