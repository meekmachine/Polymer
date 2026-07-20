(ns polymer.runtime.embody-wasm
  "Direct CLJS access to the Embody Rust/Wasm core.

  Agencies and LoomLarge tooling should load numeric kernels through this
  namespace rather than going through the TypeScript Embody facade. The Three.js
  adapter (polymer.runtime.embody / Embody class) remains responsible for scene
  graph mutation, AnimationMixer, and host writes."
  (:require ["@lovelace_lol/embody/wasm" :as wasm]))

(def ^:private *core (atom nil))
(def ^:private *init-promise (atom nil))

(defn- js-call
  [obj method & args]
  (let [f (aget obj method)]
    (when-not (fn? f)
      (throw (js/Error. (str "Embody Wasm module is missing method: " method))))
    (apply f args)))

(defn core-ready?
  "True when init-core! has resolved a Wasm module."
  []
  (some? @*core))

(defn get-core
  "Return the initialized Wasm module or throw."
  []
  (or @*core
      (throw (js/Error. "Embody Wasm core is not initialized. Call initEmbodyCore / init-core! first."))))

(defn init-core!
  "Initialize the Embody Wasm core via @lovelace_lol/embody/wasm.

  Returns a Promise that resolves to the Wasm module. Subsequent calls reuse
  the same in-flight or completed initialization."
  []
  (if-let [pending @*init-promise]
    pending
    (let [pending
          (-> (wasm/initEmbodyCore)
              (.then
               (fn [mod]
                 (let [expected (or (.-EMBODY_CORE_ABI_VERSION wasm) 1)
                       actual (js-call mod "core_abi_version")]
                   (when (not= actual expected)
                     (throw (js/Error.
                             (str "Embody Wasm ABI mismatch: runtime=" actual
                                  " expected=" expected))))
                   (reset! *core mod)
                   mod)))
              (.catch
               (fn [err]
                 (reset! *init-promise nil)
                 (throw err))))]
      (reset! *init-promise pending)
      pending)))

;; JS-friendly aliases for shadow-cljs / TypeScript consumers
(def initEmbodyCore init-core!)
(def isEmbodyCoreReady core-ready?)
(def getEmbodyCore get-core)

(defn core-abi-version
  []
  (js-call (get-core) "core_abi_version"))

(def coreAbiVersion core-abi-version)

(defn pack-morph-frame-delta
  [mesh-ids morph-target-ids values modes]
  (js-call (get-core) "pack_morph_frame_delta" mesh-ids morph-target-ids values modes))

(def packMorphFrameDelta pack-morph-frame-delta)

(defn solve-bilateral-values
  [base balance]
  (js-call (get-core) "solve_bilateral_values" base balance))

(def solveBilateralValues solve-bilateral-values)

(defn solve-morph-batch
  [values balances mix-weights]
  (js-call (get-core) "solve_morph_batch" values balances mix-weights))

(def solveMorphBatch solve-morph-batch)

(defn solve-axis-quaternion
  [axis degrees value scale]
  (js-call (get-core) "solve_axis_quaternion" axis degrees value scale))

(def solveAxisQuaternion solve-axis-quaternion)

(defn analyze-mesh-proportions
  [vertices vertical-axis]
  (js-call (get-core) "analyze_mesh_proportions" vertices vertical-axis))

(def analyzeMeshProportions analyze-mesh-proportions)

(defn solve-template-skeleton-fit
  [mesh-vertices template-bounds vertical-axis vertical-anchor]
  (js-call (get-core)
           "solve_template_skeleton_fit"
           mesh-vertices
           template-bounds
           vertical-axis
           vertical-anchor))

(def solveTemplateSkeletonFit solve-template-skeleton-fit)

(defn compose-template-fit-adjustment
  [fit scale-multiplier offset-x offset-y offset-z]
  (js-call (get-core)
           "compose_template_fit_adjustment"
           fit
           scale-multiplier
           offset-x
           offset-y
           offset-z))

(def composeTemplateFitAdjustment compose-template-fit-adjustment)

(defn compose-template-skeleton-fit-transform
  [fit-scale fit-translation manual-scale manual-translation]
  (js-call (get-core)
           "compose_template_skeleton_fit_transform"
           fit-scale
           fit-translation
           manual-scale
           manual-translation))

(def composeTemplateSkeletonFitTransform compose-template-skeleton-fit-transform)

(defn default-hair-physics-config-values
  []
  (js-call (get-core) "default_hair_physics_config_values"))

(def defaultHairPhysicsConfigValues default-hair-physics-config-values)

(defn create-hair-physics-solver
  "Construct a Wasm HairPhysicsSolver from optional packed config values."
  ([]
   (create-hair-physics-solver (default-hair-physics-config-values)))
  ([config-values]
   (let [Ctor (aget (get-core) "HairPhysicsSolver")]
     (when-not Ctor
       (throw (js/Error. "Embody Wasm module does not export HairPhysicsSolver")))
     (new Ctor config-values))))

(def createHairPhysicsSolver create-hair-physics-solver)

(defn create-runtime-core
  "Construct the Wasm live morph RuntimeCore (AU/viseme state -> packed
  morph FrameDelta rows). Load bindings with load_au_morph_bindings /
  load_viseme_morph_bindings before evaluating."
  [viseme-slot-count]
  (let [Ctor (aget (get-core) "RuntimeCore")]
    (when-not Ctor
      (throw (js/Error. "Embody Wasm module does not export RuntimeCore")))
    (new Ctor viseme-slot-count)))

(def createRuntimeCore create-runtime-core)

;; Re-export ABI constants from the JS wasm entry for consumers that need them
;; before init completes.
(def EMBODY_CORE_ABI_VERSION (or (.-EMBODY_CORE_ABI_VERSION wasm) 1))
(def PACKED_MORPH_FRAME_DELTA_STRIDE (.-PACKED_MORPH_FRAME_DELTA_STRIDE wasm))
(def HAIR_CONFIG_STRIDE (.-HAIR_CONFIG_STRIDE wasm))
(def HAIR_STATE_STRIDE (.-HAIR_STATE_STRIDE wasm))
(def HAIR_HEAD_STATE_STRIDE (.-HAIR_HEAD_STATE_STRIDE wasm))
(def HAIR_MORPH_OUTPUT_STRIDE (.-HAIR_MORPH_OUTPUT_STRIDE wasm))
(def MESH_PROPORTIONS_STRIDE (.-MESH_PROPORTIONS_STRIDE wasm))
(def TEMPLATE_SKELETON_FIT_SOLUTION_STRIDE (.-TEMPLATE_SKELETON_FIT_SOLUTION_STRIDE wasm))
(def TEMPLATE_SKELETON_FIT_TRANSFORM_STRIDE (.-TEMPLATE_SKELETON_FIT_TRANSFORM_STRIDE wasm))
