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
