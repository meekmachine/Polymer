(ns polymer.runtime.embody
  (:require ["@lovelace_lol/embody" :as embody]))

;; Polymer is the package boundary for the character runtime. LoomLarge should
;; import these runtime helpers from Polymer, not from Embody directly. That
;; keeps the current Embody/Three.js implementation replaceable later by a
;; different runtime package without another LoomLarge-wide import migration.

(def Embody (.-Embody embody))
(def BLENDING_MODES (or (.-BLENDING_MODES embody)
                        (.-THREE_BLENDING_MODES embody)))
(def THREE_BLENDING_MODES (.-THREE_BLENDING_MODES embody))
(def collectMorphMeshes (.-collectMorphMeshes embody))

(def analyzeModel (.-analyzeModel embody))
(def extractModelData (.-extractModelData embody))
(def getPreset (.-getPreset embody))
(def validateMappings (.-validateMappings embody))
(def LIP_SYNC_TO_BONES (.-LIP_SYNC_TO_BONES embody))

(def extendCharacterConfigWithPreset (.-extendCharacterConfigWithPreset embody))
(def extractProfileOverrides (.-extractProfileOverrides embody))
(def mergeCharacterRegionsByName (.-mergeCharacterRegionsByName embody))

(def computeCameraRelativeGazeOffset (.-computeCameraRelativeGazeOffset embody))
(def detectAnnotationLaterality (.-detectAnnotationLaterality embody))
(def fuzzyNameMatch (.-fuzzyNameMatch embody))
(def getDefaultAnnotationLaterality (.-getDefaultAnnotationLaterality embody))
(def getMeshNamesForAUProfile (.-getMeshNamesForAUProfile embody))
(def getModelLocalOrbitAngle (.-getModelLocalOrbitAngle embody))
(def getSemanticHorizontalSign (.-getSemanticHorizontalSign embody))
(def getSemanticHorizontalSignForSide (.-getSemanticHorizontalSignForSide embody))
(def getWorldDirectionForCameraAngle (.-getWorldDirectionForCameraAngle embody))
(def hasLeftRightMorphs (.-hasLeftRightMorphs embody))
(def isMixedAU (.-isMixedAU embody))
(def passesMarkerCameraAngleGate (.-passesMarkerCameraAngleGate embody))
(def resolveBoneNames (.-resolveBoneNames embody))
(def resolveFaceCenter (.-resolveFaceCenter embody))
(def resolveRegionCameraAngle (.-resolveRegionCameraAngle embody))
(def resolveRegionVisibilityCameraAngle (.-resolveRegionVisibilityCameraAngle embody))
(def toWorldDirection (.-toWorldDirection embody))

(def VISEME_KEYS (.-VISEME_KEYS embody))
