(ns polymer.runtime.embody-wasm-test
  (:require [cljs.test :refer [deftest is async]]
            [polymer.runtime.embody-wasm :as embody-wasm]))

(defn- make-configured-core []
  (let [core (embody-wasm/create-runtime-core 0)
        profile #js {:auToMorphs #js {"12" #js {:left #js [] :right #js [] :center #js ["Smile"]}}
                     :morphToMesh #js {:face #js ["FaceMesh"]}}
        model #js {:meshes #js [#js {:id 1 :name "FaceMesh" :morphTargetIds #js [7]}]
                   :morphTargets #js [#js {:id 7 :meshId 1 :name "Smile" :hostIndex 0}]
                   :bones #js []}]
    (.configure core (js/JSON.stringify profile) (js/JSON.stringify model))
    core))

(deftest init-core-abi-version
  (async done
    (-> (embody-wasm/init-core!)
        (.then
         (fn [_]
           (is (true? (embody-wasm/core-ready?)))
           (is (= embody-wasm/EMBODY_CORE_ABI_VERSION (embody-wasm/core-abi-version)))
           (is (number? embody-wasm/PACKED_MORPH_FRAME_DELTA_STRIDE))
           (done)))
        (.catch
         (fn [err]
           (is false (str "init-core! failed: " err))
           (done))))))

(deftest runtime-core-evaluates-packed-morph-frame-delta
  (async done
    (-> (embody-wasm/init-core!)
        (.then
         (fn [_]
           (let [core (embody-wasm/create-runtime-core 0)]
             ;; AU 1 center -> mesh 10 / morph 100
             (.load_au_morph_bindings core (js/Float32Array. #js [1 2 10 100 1]))
             (.set_au core 1 0.5 0)
             (let [packed (.evaluate_morph_frame_delta core)]
               (is (= embody-wasm/PACKED_MORPH_FRAME_DELTA_STRIDE (.-length packed)))
               (is (= 10 (aget packed 0)))
               (is (= 100 (aget packed 1)))
               (is (< (js/Math.abs (- 0.5 (aget packed 2))) 1e-6)))
             (done))))
        (.catch
         (fn [err]
           (is false (str "runtime core test failed: " err))
           (done))))))

(deftest runtime-core-self-configures-from-profile-json
  (async done
    (-> (embody-wasm/init-core!)
        (.then
         (fn [_]
           (let [core (make-configured-core)]
             (.set_au_signed core 12 0.75 js/NaN)
             (let [packed (.evaluate_morph_frame_delta core)]
               (is (= embody-wasm/PACKED_MORPH_FRAME_DELTA_STRIDE (.-length packed)))
               (is (= 1 (aget packed 0)))
               (is (= 7 (aget packed 1)))
               (is (< (js/Math.abs (- 0.75 (aget packed 2))) 1e-6)))
             (done))))
        (.catch
         (fn [err]
           (is false (str "configure test failed: " err))
           (done))))))

(deftest runtime-core-runs-transitions-via-update
  (async done
    (-> (embody-wasm/init-core!)
        (.then
         (fn [_]
           (let [core (make-configured-core)]
             (.transition_au core 12 1.0 200 js/NaN)
             (is (= 1 (.active_transition_count core)))
             (.update core 0.1) ;; easeInOutQuad(0.5) = 0.5
             (is (< (js/Math.abs (- 0.5 (.get_au core 12))) 1e-6))
             (.update core 0.2)
             (is (< (js/Math.abs (- 1.0 (.get_au core 12))) 1e-6))
             (is (zero? (.active_transition_count core)))
             (done))))
        (.catch
         (fn [err]
           (is false (str "transition test failed: " err))
           (done))))))
