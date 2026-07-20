(ns polymer.runtime.embody-wasm-test
  (:require [cljs.test :refer [deftest is async]]
            [polymer.runtime.embody-wasm :as embody-wasm]))

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
