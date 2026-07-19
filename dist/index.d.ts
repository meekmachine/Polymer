import type { Embody, EmbodyRuntime } from '@lovelace_lol/embody';

export {
  Embody,
  BLENDING_MODES,
  THREE_BLENDING_MODES,
  collectMorphMeshes,
  analyzeModel,
  extractModelData,
  getPreset,
  validateMappings,
  extendCharacterConfigWithPreset,
  extractProfileOverrides,
  mergeCharacterRegionsByName,
  applyAUBoneBindingUpdate,
  applyBilateralAxisBindingUpdate,
  applyBoneAxisBindingUpdate,
  buildBoneAuOptions,
  classifyAuAsJointControl,
  createBilateralBoneAxisAu,
  createBoneAxisAu,
  DEFAULT_AXIS_TO_CHANNEL,
  DEFAULT_BONE_MAX_DEGREES,
  ensureBilateralBoneNodeKeys,
  ensureBoneNodeKey,
  findNodeKeyForBone,
  formatAxisDirectionLabel,
  formatAxisLabel,
  getAUBoneBindingState,
  getAxisFromChannel,
  getBilateralAxisBindingState,
  getBoneAxisBindingState,
  inferChiralBoneNamePair,
  inferEyeControlFamily,
  inferEyeControlScope,
  isJointControlAuInfo,
  isMaxDegreesOnlyAxisBindingUpdate,
  JOINT_CONTROL_SECTION,
  resolveBoneAxisChannel,
  resolveBoneNameForNodeKey,
  resolveContinuumDisplayLabel,
  stripConfiguredBoneAffixes,
  computeCameraRelativeGazeOffset,
  detectAnnotationLaterality,
  fuzzyNameMatch,
  getDefaultAnnotationLaterality,
  getMeshNamesForAUProfile,
  getModelLocalOrbitAngle,
  getSemanticHorizontalSign,
  getSemanticHorizontalSignForSide,
  getWorldDirectionForCameraAngle,
  hasLeftRightMorphs,
  isMixedAU,
  passesMarkerCameraAngleGate,
  resolveBoneNames,
  resolveFaceCenter,
  resolveRegionCameraAngle,
  resolveRegionVisibilityCameraAngle,
  toWorldDirection,
  CC4_BONES,
  VISEME_KEYS,
} from '@lovelace_lol/embody';

export type {
  AUBoneBindingState,
  AUInfo,
  AnimationClipInfo,
  AnimationInfo,
  AnnotationLaterality,
  BilateralAxisBindingState,
  BilateralAxisDirectionScaleState,
  BilateralAxisScopeBindingState,
  BilateralBoneAxisScope,
  BoneAxisBindingState,
  BoneAxisBindingUpdate,
  BoneAxisDirection,
  BoneAxisDirectionScale,
  BoneAxisKey,
  BoneBinding,
  BoneControlFamily,
  BoneControlScope,
  BoneInfo,
  CameraRelativeGazeOffset,
  CameraRelativeGazeOptions,
  CharacterConfig,
  CharacterRegistry,
  ChiralBoneNamePair,
  CompositeRotation,
  CreatedBoneAxisAu,
  ExpandAnimation,
  ExpandedRegionState,
  FallbackConfig,
  LineConfig,
  LineCurve,
  LineStyle,
  Embody,
  EmbodyRuntime,
  MarkerGroup,
  MarkerStyle,
  MarkerStyleOverrides,
  MeshInfo,
  ModelAnalysisReport,
  ModelData,
  MorphTargetsBySide,
  NamedDirection,
  PresetType,
  Profile,
  Region,
  RotationAxis,
  RotationChannel,
  TrackInfo,
  TransitionHandle,
  ValidationResult,
  VisemeSlot,
} from '@lovelace_lol/embody';

export interface BlinkState {
  agency: 'blink';
  enabled: boolean;
  frequency: number;
  duration: number;
  intensity: number;
  randomness: number;
  leftEyeIntensity: number | null;
  rightEyeIntensity: number | null;
  burstEnabled: boolean;
  burstFrequency: number;
  burstCount: number;
  burstGap: number;
  lastBlinkTime: number | null;
  scheduledBlinkCount: number;
  scheduledBurstCount: number;
}

export type BlinkAgencyConfig = Partial<Omit<BlinkState, 'agency'>>;

export interface GazeTarget {
  x: number;
  y: number;
  z?: number;
}

export interface GazeConfig {
  enabled?: boolean;
  eyesEnabled?: boolean;
  headEnabled?: boolean;
  headFollowEyes?: boolean;
  mirrored?: boolean;
  smoothFactor?: number;
  minDelta?: number;
  transitionDurationMs?: number;
  eyeIntensity?: number;
  headIntensity?: number;
  coalesceMs?: number;
}

export interface GazeState {
  agency: 'gaze';
  status: string;
  mode: string;
  active: boolean;
  rawTarget: Required<GazeTarget>;
  target: Required<GazeTarget>;
  baseTarget: Required<GazeTarget>;
  cameraRelativeOffset: Required<GazeTarget>;
  lastCameraFact: null | Record<string, unknown>;
  lastRequestedTarget: Required<GazeTarget>;
  pendingRequest: GazeLookRequest | null;
  lastRequest: GazeLookRequest | GazeResetRequest | null;
  lastIgnored: null | Record<string, unknown>;
  lastPlan: null | Record<string, unknown>;
  lastEvent: null | Record<string, unknown>;
  receivedCount: number;
  plannedCount: number;
  requestedCount: number;
  ignoredCount: number;
  resetCount: number;
  cancelCount: number;
  config: Required<GazeConfig>;
}

export interface GazeApplyOptions {
  eyeEnabled?: boolean;
  headEnabled?: boolean;
  headFollowEyes?: boolean;
  force?: boolean;
}

export interface GazeAttentionCandidate {
  target?: GazeTarget;
  gazeTarget?: GazeTarget;
  lookTarget?: GazeTarget;
  x?: number;
  y?: number;
  z?: number;
  priority?: number;
  weight?: number;
  confidence?: number;
  source?: string;
  label?: string;
  id?: string;
}

export type GazeDispatch =
  | { type: 'configure'; config: GazeConfig }
  | { type: 'setMode' | 'set-mode'; mode: string }
  | { type: 'setActive' | 'set-active'; active: boolean }
  | { type: 'enable' }
  | { type: 'disable' }
  | { type: 'setTarget' | 'set-target' | 'focusTarget'; target: GazeTarget; options?: GazeApplyOptions }
  | { type: 'attention.fact'; targets?: GazeAttentionCandidate[]; target?: GazeTarget; source?: string }
  | { type: 'camera.fact'; relativeOffset: GazeTarget; source?: string }
  | { type: 'camera.stale' | 'clearCameraOffset'; reason?: string; source?: string }
  | { type: 'reset'; durationMs?: number; eyes?: boolean; head?: boolean }
  | { type: 'cancel'; reason?: string };

export interface GazeLookRequest {
  type: 'eyeHeadTracking.requestGaze';
  agency: 'gaze';
  targetAgency: 'eyeHeadTracking';
  requestId: string;
  source?: string;
  label?: string;
  mode: string;
  target: Required<GazeTarget>;
  rawTarget: Required<GazeTarget>;
  previousTarget: Required<GazeTarget>;
  eyeEnabled: boolean;
  headEnabled: boolean;
  headFollowEyes: boolean;
  eyeIntensity: number;
  headIntensity: number;
  eyeDurationMs: number;
  headDurationMs: number;
  createdAt: number;
  queuedAt?: number;
  publishedAt?: number;
}

export interface GazeResetRequest {
  type: 'eyeHeadTracking.requestReset';
  agency: 'gaze';
  targetAgency: 'eyeHeadTracking';
  requestId: string;
  durationMs: number;
  eyes: boolean;
  head: boolean;
  requestedAt: number;
}

export interface GazeCancelRequest {
  type: 'eyeHeadTracking.requestCancel';
  agency: 'gaze';
  targetAgency: 'eyeHeadTracking';
  requestId: string;
  reason: string;
  requestedAt: number;
}

export interface EyeHeadTrackingConfig {
  enabled?: boolean;
  eyeTrackingEnabled?: boolean;
  headTrackingEnabled?: boolean;
  headFollowEyes?: boolean;
  eyeIntensity?: number;
  headIntensity?: number;
  headRoll?: number;
  eyePriority?: number;
  headPriority?: number;
  snippetPriority?: number;
  transitionDurationMs?: number;
  returnToCenterDurationMs?: number;
  coalesceMs?: number;
  replaceExisting?: boolean;
}

export interface EyeHeadTrackingState {
  agency: 'eyeHeadTracking';
  status: string;
  mode: string;
  currentTarget: Required<GazeTarget>;
  pendingRequest: EyeHeadGazeRequest | null;
  lastRequest: EyeHeadGazeRequest | null;
  lastSnippet: PolymerAnimationSnippet | null;
  activeSnippetNames: string[];
  lastIgnored: null | Record<string, unknown>;
  lastPlan: null | Record<string, unknown>;
  scheduledCount: number;
  removedCount: number;
  resetCount: number;
  cancelCount: number;
  ignoredCount: number;
  config: Required<EyeHeadTrackingConfig>;
}

export interface EyeHeadGazeRequest {
  type: 'eyeHeadTracking.requestGaze';
  agency: 'eyeHeadTracking' | 'gaze';
  sourceAgency?: string;
  requestId: string;
  target: Required<GazeTarget>;
  rawTarget?: Required<GazeTarget>;
  mode?: string;
  eyeEnabled?: boolean;
  headEnabled?: boolean;
  headFollowEyes?: boolean;
  eyeIntensity?: number;
  headIntensity?: number;
  headRoll?: number;
  eyeDurationMs?: number;
  headDurationMs?: number;
  createdAt?: number;
}

export type EyeHeadTrackingDispatch =
  | { type: 'configure'; config: EyeHeadTrackingConfig }
  | { type: 'enable' }
  | { type: 'disable' }
  | { type: 'setTarget' | 'set-target' | 'requestGaze'; target: GazeTarget }
  | EyeHeadGazeRequest
  | { type: 'reset' | 'eyeHeadTracking.requestReset'; durationMs?: number; eyes?: boolean; head?: boolean; reason?: string }
  | { type: 'cancel' | 'eyeHeadTracking.requestCancel'; reason?: string };

export interface BlinkTriggerOptions {
  intensity?: number;
  duration?: number;
  burstCount?: number;
  burstGap?: number;
}

export type BlinkDispatch =
  | { type: 'enable' }
  | { type: 'disable' }
  | { type: 'triggerBlink'; options?: BlinkTriggerOptions }
  | { type: 'requestBlink'; reason?: string; options?: BlinkTriggerOptions }
  | { type: 'setFrequency'; value: number }
  | { type: 'setDuration'; value: number }
  | { type: 'setIntensity'; value: number }
  | { type: 'setRandomness'; value: number }
  | { type: 'setLeftEyeIntensity'; value: number | null }
  | { type: 'setRightEyeIntensity'; value: number | null }
  | { type: 'setBurstEnabled'; value: boolean }
  | { type: 'setBurstFrequency'; value: number }
  | { type: 'setBurstCount'; value: number }
  | { type: 'setBurstGap'; value: number }
  | { type: 'configure'; config: BlinkAgencyConfig }
  | { type: 'reset' };

export interface AnimationScheduledSnippet {
  name: string;
  sourceAgency: string;
  snippetCategory?: string;
  snippetPriority?: number;
  maxTime?: number;
  loop: boolean;
  autoPlay: boolean;
  requestedAt: number;
}

export interface AnimationState {
  agency: 'animation';
  scheduled: Record<string, AnimationScheduledSnippet>;
  scheduledCount: number;
  startedCount: number;
  seekCount: number;
  updatedCount: number;
  removedCount: number;
  lastEvent: null | {
    type:
      | 'animationSnippetScheduled'
      | 'animationSnippetStarted'
      | 'animationSnippetSeeked'
      | 'animationSnippetUpdated'
      | 'animationSnippetRemoved';
    name: string;
    sourceAgency: string;
    reason?: string;
    offsetSec?: number;
    params?: Record<string, unknown>;
    at: number;
  };
}

export interface AnimationAgencyConfig {
  runtime?: PolymerAnimationRuntime;
  engine?: Embody | EmbodyRuntime;
  runtimeConfig?: Record<string, unknown>;
}

export type AnimationDispatch =
  | {
      type: 'scheduleSnippet';
      sourceAgency?: string;
      snippet: PolymerAnimationSnippet;
      options?: { autoPlay?: boolean; sourceAgency?: string; [key: string]: unknown };
    }
  | { type: 'seekSnippet'; sourceAgency?: string; name: string; offsetSec: number }
  | { type: 'updateSnippet'; sourceAgency?: string; name: string; params: Record<string, unknown> }
  | { type: 'removeSnippet'; sourceAgency?: string; name: string }
  | { type: 'clear'; sourceAgency?: string };

export interface PolymerSnippetKeyframe {
  time: number;
  intensity: number;
  inherit?: boolean;
}

export type PolymerSnippetChannelTarget =
  | { type: 'au'; id: number; balance?: number }
  | { type: 'viseme'; id: number; meshNames?: string[] }
  | { type: 'morph'; id: string | number; meshNames?: string[] }
  | {
      type: 'bone';
      id: string;
      channel: 'rx' | 'ry' | 'rz' | 'tx' | 'ty' | 'tz';
      scale?: number;
      maxDegrees?: number;
      maxUnits?: number;
    };

export interface PolymerSnippetChannel {
  target: PolymerSnippetChannelTarget;
  keyframes: PolymerSnippetKeyframe[];
  intensityScale?: number;
}

export interface PolymerAnimationHandle {
  play?: () => unknown;
  stop?: () => unknown;
  setTime?: (offsetSec: number) => unknown;
  seek?: (offsetSec: number) => unknown;
  updateParams?: (params: Record<string, unknown>) => unknown;
  setParams?: (params: Record<string, unknown>) => unknown;
  finished?: Promise<unknown> | { then: (onFulfilled?: () => unknown, onRejected?: (error: unknown) => unknown) => unknown };
}

export interface PolymerAnimationRuntime {
  buildClip?: (
    name: string,
    curves: Record<string, PolymerSnippetKeyframe[]>,
    options?: Record<string, unknown>
  ) => PolymerAnimationHandle | unknown;
  playSnippet?: (
    name: string,
    curves: Record<string, PolymerSnippetKeyframe[]>,
    options?: Record<string, unknown>
  ) => PolymerAnimationHandle | unknown;
  playTypedSnippet?: (
    snippet: { name: string; channels: PolymerSnippetChannel[] },
    options?: Record<string, unknown>
  ) => PolymerAnimationHandle | unknown;
  buildTypedClip?: (
    name: string,
    channels: PolymerSnippetChannel[],
    options?: Record<string, unknown>
  ) => PolymerAnimationHandle | unknown;
  updateClipParams?: (name: string, params: Record<string, unknown>) => unknown;
  setSnippetTime?: (name: string, offsetSec: number) => unknown;
  seekSnippet?: (name: string, offsetSec: number) => unknown;
  seek?: (name: string, offsetSec: number) => unknown;
  cleanupSnippet?: (name: string) => unknown;
  stopAnimation?: (name: string) => unknown;
  getAnimationState?: (name: string) => unknown;
}

export interface PolymerAnimationSnippet {
  name: string;
  curves?: Record<string, PolymerSnippetKeyframe[]>;
  channels?: PolymerSnippetChannel[];
  maxTime: number;
  loop: boolean;
  snippetCategory?: string;
  snippetPriority: number;
  snippetPlaybackRate: number;
  snippetIntensityScale: number;
  snippetJawScale?: number;
  autoVisemeJaw?: boolean;
  metadata?: Record<string, unknown>;
}

export interface LipSyncConfig {
  intensity?: number;
  speechRate?: number;
  jawScale?: number;
  /** Scale for independently planned tongue AU curves. Set 0 to disable tongue motion. */
  tongueScale?: number;
  rampMs?: number;
  holdMs?: number;
  priority?: number;
  visualLeadMs?: number;
  wordDriftThresholdSec?: number;
}

export interface LipSyncVisemeEvent {
  visemeId: number;
  /**
   * Optional independent jaw-axis activation for this event. When omitted,
   * Polymer derives a default from the canonical viseme slot. Set to 0 for a
   * lip-only viseme, or scale with LipSyncConfig.jawScale for JALI-style control.
   */
  jawActivation?: number;
  phoneme?: string;
  /**
   * Primary visual-speech class for planner rules, e.g. vowel, bilabial,
   * labiodental, sibilant, obstruent, nasal, tongue, liquid, glide, or pause.
   */
  phonemeClass?: string;
  /**
   * Full class set for phonemes that need multiple JALI-style rules, such as
   * M being both bilabial and nasal or F being labiodental and fricative.
   */
  phonemeClasses?: string[];
  offsetMs: number;
  durationMs: number;
}

export interface LipSyncWordTiming {
  word: string;
  startSec?: number;
  endSec?: number;
  start?: number;
  end?: number;
  start_time?: number;
  end_time?: number;
}

export interface AzureVisemeEvent {
  id?: number;
  visemeId?: number;
  viseme_id?: number;
  time?: number;
  audio_offset?: number;
  audioOffset?: number;
}

export interface LipSyncTimeline {
  name?: string;
  text?: string;
  source?: 'text' | 'azure' | 'livekit' | 'webSpeech' | string;
  visemes: LipSyncVisemeEvent[];
  wordTimings?: LipSyncWordTiming[];
  durationSec?: number;
}

export interface LipSyncState {
  agency: 'lipSync';
  speaking: boolean;
  currentWord: string | null;
  currentViseme: number | null;
  snippetName: string | null;
  source: string | null;
  text: string | null;
  startTime: number | null;
  audioStartedAt: number | null;
  audioTimeSec: number | null;
  maxTime: number;
  wordIndex: number;
  wordTimings: Array<{ word: string; startSec: number; endSec: number }>;
  scheduledCount: number;
  stoppedCount: number;
  syncCorrectionCount: number;
  config: Required<LipSyncConfig>;
  lastEvent: null | Record<string, unknown>;
}

export type LipSyncDispatch =
  | { type: 'configure'; config: LipSyncConfig }
  | {
      type: 'startText';
      text: string;
      name?: string;
      source?: string;
      wordTimings?: LipSyncWordTiming[];
      durationSec?: number;
      totalDurationMs?: number;
    }
  | { type: 'startTimeline'; timeline: LipSyncTimeline }
  | {
      type: 'processAzureVisemes';
      visemes: AzureVisemeEvent[];
      totalDurationMs?: number;
      name?: string;
      text?: string;
      source?: string;
      wordTimings?: LipSyncWordTiming[];
      options?: { wordTimings?: LipSyncWordTiming[]; visualLeadMs?: number; [key: string]: unknown };
    }
  | { type: 'wordBoundary'; word: string; wordIndex?: number; observedElapsedSec?: number; hostElapsedSec?: number }
  | { type: 'audioStarted'; name?: string; audioTimeSec?: number; currentTimeSec?: number; offsetSec?: number }
  | { type: 'audioTime'; name?: string; audioTimeSec?: number; currentTimeSec?: number; offsetSec?: number }
  | { type: 'updateWordTimings'; wordTimings: LipSyncWordTiming[] }
  | { type: 'stop' }
  | { type: 'reset' };

export interface TTSVoice {
  id: string;
  name: string;
  language?: string;
  lang?: string;
  gender?: string;
  styles?: string[];
  provider?: 'webSpeech' | 'azure' | string;
}

export interface TTSConfig {
  engine?: 'webSpeech' | 'azure' | 'livekit' | string;
  backendUrl?: string;
  voiceName?: string;
  azureVoiceName?: string;
  azureStyle?: string;
  azureStyleDegree?: string | number | null;
  rate?: number;
  pitch?: number;
  volume?: number;
  visualLeadMs?: number;
  lipsyncIntensity?: number;
  jawScale?: number;
  /** Scale for LipSync tongue AU planning forwarded by TTS before each speech session. */
  tongueScale?: number;
  webSpeechDriftThresholdSec?: number;
  azureDriftThresholdSec?: number;
  azureCacheLimit?: number;
  debug?: boolean;
  providers?: Record<string, unknown>;
}

export interface TTSState {
  agency: 'tts';
  status: 'idle' | 'loading' | 'speaking' | 'error' | string;
  engine: string;
  speaking: boolean;
  currentText: string | null;
  snippetName: string | null;
  sessionId: number;
  startedAt: number | null;
  endedAt: number | null;
  wordIndex: number;
  webSpeechVoices: TTSVoice[];
  azureVoices: TTSVoice[];
  azureStatus: 'checking' | 'ready' | 'error' | string;
  azureStatusMessage: string;
  lastPlan: Record<string, unknown> | null;
  lastError: string | null;
  config: Required<Omit<TTSConfig, 'providers'>> & { providers?: never };
}

export type TTSDispatch =
  | { type: 'configure'; config: TTSConfig }
  | { type: 'loadVoices'; engine?: 'webSpeech' | 'azure' | string }
  | {
      type: 'speak';
      text: string;
      engine?: 'webSpeech' | 'azure' | 'livekit' | string;
      name?: string;
      backendUrl?: string;
      voiceName?: string;
      style?: string;
      rate?: number;
      pitch?: number;
      volume?: number;
      visualLeadMs?: number;
    }
  | { type: 'stop' }
  | { type: 'reset' };

export interface ProsodicConfig {
  enabled?: boolean;
  intensity?: number;
  priority?: number;
  speechGestureEvery?: number;
  blinkFastCooldownMs?: number;
}

export interface ProsodicState {
  agency: 'prosodic';
  speaking: boolean;
  wordIndex: number;
  currentWord: string | null;
  activeSnippets: string[];
  scheduledCount: number;
  removedCount: number;
  lastGesture: string | null;
  lastBlinkFastCueAt: number;
  config: Required<ProsodicConfig>;
  lastEvent: null | Record<string, unknown>;
}

export type ProsodicDispatch =
  | { type: 'configure'; config: ProsodicConfig }
  | { type: 'speechStarted'; name?: string; sourceAgency?: string; engine?: string }
  | { type: 'speechStopped'; reason?: string; sourceAgency?: string }
  | {
      type: 'wordBoundary';
      word: string;
      wordIndex?: number;
      observedElapsedSec?: number;
      hostElapsedSec?: number;
      sourceAgency?: string;
    }
  | { type: 'blinkFast'; sourceAgency?: string; plan?: Record<string, unknown> }
  | { type: 'stop'; reason?: string }
  | { type: 'reset' };

export interface ConversationConfig {
  autoRespond?: boolean;
  maxHistory?: number;
  responseSource?: string;
  ttsAgency?: string;
  interruptionMode?: string;
}

export interface ConversationHistoryEntry {
  role: 'user' | 'agent' | string;
  text: string;
  source?: string;
  at?: number;
  turnId?: string | null;
}

export interface ConversationState {
  agency: 'conversation';
  status: string;
  started: boolean;
  turnId: string | null;
  history: ConversationHistoryEntry[];
  context: Record<string, unknown>;
  pendingResponse: Record<string, unknown> | null;
  lastUserText: string | null;
  lastAgentText: string | null;
  lastEvent: Record<string, unknown> | null;
  interrupted: boolean;
  startedCount: number;
  stoppedCount: number;
  userUtteranceCount: number;
  agentUtteranceCount: number;
  responseRequestCount: number;
  ttsRequestCount: number;
  cancelCount: number;
  config: Required<ConversationConfig>;
}

export type ConversationDispatch =
  | { type: 'configure'; config: ConversationConfig }
  | { type: 'start' }
  | { type: 'stop'; reason?: string }
  | { type: 'reset' }
  | { type: 'cancel' | 'interrupt'; reason?: string }
  | { type: 'transcript.final' | 'transcriptFinal' | 'userUtterance'; text?: string; transcript?: string; utterance?: string; responseText?: string; agentText?: string; source?: string }
  | { type: 'agentUtterance' | 'responseReady'; text?: string; utterance?: string; responseText?: string; agentText?: string; source?: string }
  | { type: 'tts.status'; status: string };

export interface TranscriptionConfig {
  provider?: string;
  lang?: string;
  continuous?: boolean;
  interimResults?: boolean;
  maxAlternatives?: number;
  maxRetries?: number;
  retryDelayMs?: number;
  minConfidence?: number;
  agentFilteringEnabled?: boolean;
  interruptDetectionEnabled?: boolean;
}

export interface TranscriptionState {
  agency: 'transcription';
  status: string;
  active: boolean;
  sessionId: string | null;
  sequence: number;
  currentTranscript: string | null;
  isFinal: boolean;
  lastPartial: Record<string, unknown> | null;
  lastFinal: Record<string, unknown> | null;
  lastError: string | null;
  lastEvent: Record<string, unknown> | null;
  retryCount: number;
  startedCount: number;
  stoppedCount: number;
  partialCount: number;
  finalCount: number;
  errorCount: number;
  config: Required<TranscriptionConfig>;
}

export type TranscriptionDispatch =
  | { type: 'configure'; config: TranscriptionConfig }
  | { type: 'start' }
  | { type: 'stop' | 'cancel'; reason?: string }
  | { type: 'reset' }
  | { type: 'providerPartial' | 'partialTranscript' | 'providerFinal' | 'finalTranscript'; text?: string; transcript?: string; confidence?: number; source?: string }
  | { type: 'providerError'; message?: string; error?: string };

export interface HairColorConfig {
  name?: string;
  baseColor?: string;
  emissive?: string;
  emissiveIntensity?: number;
}

export interface HairPhysicsConfig {
  enabled?: boolean;
  stiffness?: number;
  damping?: number;
  inertia?: number;
  gravity?: number;
  responseScale?: number;
  idleSwayAmount?: number;
  idleSwaySpeed?: number;
  windStrength?: number;
  windDirectionX?: number;
  windDirectionZ?: number;
  windTurbulence?: number;
  windFrequency?: number;
  idleClipDurationMs?: number;
  impulseClipDurationMs?: number;
  coalesceMs?: number;
}

export interface HairObjectRef {
  name: string;
  isEyebrow?: boolean;
  isMesh?: boolean;
}

export interface HairConfig {
  physics?: HairPhysicsConfig;
  hairColor?: HairColorConfig;
  eyebrowColor?: HairColorConfig;
  showOutline?: boolean;
  outlineColor?: string;
  outlineOpacity?: number;
  objects?: HairObjectRef[];
  parts?: Record<string, unknown>;
}

export interface HairState {
  agency: 'hair';
  status: string;
  hairColor: Required<HairColorConfig>;
  eyebrowColor: Required<HairColorConfig>;
  showOutline: boolean;
  outlineColor: string;
  outlineOpacity: number;
  objects: HairObjectRef[];
  parts: Record<string, unknown>;
  physics: Required<HairPhysicsConfig>;
  lastMotion: Record<string, unknown> | null;
  lastRuntimeRequest: Record<string, unknown> | null;
  lastEvent: Record<string, unknown> | null;
  motionCount: number;
  runtimeRequestCount: number;
  resetCount: number;
  config: Required<Omit<HairConfig, 'physics' | 'hairColor' | 'eyebrowColor' | 'objects'>> & {
    physics: Required<HairPhysicsConfig>;
    hairColor: Required<HairColorConfig>;
    eyebrowColor: Required<HairColorConfig>;
    objects: HairObjectRef[];
  };
}

export type HairDispatch =
  | { type: 'configure'; config: HairConfig }
  | { type: 'registerObjects'; objects: HairObjectRef[] }
  | { type: 'motionFact'; velocity?: GazeTarget; delta?: GazeTarget; x?: number; y?: number; z?: number }
  | { type: 'reset' };

export interface CameraContextConfig {
  coalesceMs?: number;
  staleAfterMs?: number;
  epsilon?: number;
  yawWeight?: number;
  pitchWeight?: number;
}

export interface PolymerVector3 {
  x: number;
  y: number;
  z: number;
}

export interface PolymerQuaternion {
  x: number;
  y: number;
  z: number;
  w: number;
}

export interface CameraContextState {
  agency: 'cameraContext';
  status: string;
  cameraPosition: PolymerVector3 | null;
  targetPosition: PolymerVector3 | null;
  modelQuaternion: PolymerQuaternion;
  relativeOffset: Required<GazeTarget>;
  lastUpdatedAt: number | null;
  lastPublishedAt: number | null;
  lastFact: Record<string, unknown> | null;
  lastPlan: Record<string, unknown> | null;
  lastInvalidation: Record<string, unknown> | null;
  stale: boolean;
  invalidated: boolean;
  updateCount: number;
  publishedCount: number;
  config: Required<CameraContextConfig>;
}

export type CameraContextDispatch =
  | { type: 'configure'; config: CameraContextConfig }
  | {
      type: 'updateCamera';
      facts?: Record<string, unknown>;
      cameraPosition?: PolymerVector3;
      targetPosition?: PolymerVector3;
      modelQuaternion?: PolymerQuaternion;
      source?: string;
    }
  | { type: 'publishCameraFacts' }
  | { type: 'invalidateStale'; reason?: string }
  | { type: 'reset' };

export type LipSyncSchedulerQueueEntry =
  | {
      type: 'scheduleAnimation';
      agency: 'lipSync';
      requestId: string;
      snippetName: string;
      effectors: string[];
      queueIndex: number;
      queuedAt: number;
    }
  | {
      type: 'seekAnimation';
      agency: 'lipSync';
      requestId: string;
      name: string;
      offsetSec: number;
      reason: string;
      queueIndex: number;
      queuedAt: number;
    }
  | {
      type: 'removeAnimation';
      agency: 'lipSync';
      requestId: string;
      name: string;
      reason: string;
      queueIndex: number;
      queuedAt: number;
    }
  | {
      type: 'finishTimeline';
      agency: 'lipSync';
      reason: string;
      queueIndex: number;
      queuedAt: number;
    };

export type ProsodicSchedulerQueueEntry =
  | {
      type: 'scheduleAnimation';
      agency: 'prosodic';
      requestId: string;
      snippetName: string;
      effectors: string[];
      queueIndex: number;
      queuedAt: number;
    }
  | {
      type: 'removeAnimation';
      agency: 'prosodic';
      requestId: string;
      name: string;
      reason: string;
      queueIndex: number;
      queuedAt: number;
    };

export type TTSSchedulerQueueEntry =
  | {
      type: 'webSpeechStartFallback';
      agency: 'tts';
      sessionId: number;
      delayMs: number;
      queueIndex: number;
      queuedAt: number;
    }
  | {
      type: 'audioBoundaryPolling';
      agency: 'tts';
      sessionId: number;
      wordCount: number;
      queueIndex: number;
      queuedAt: number;
    };

export type GestureScope = 'left' | 'right' | 'both' | 'custom' | string;

export type GestureCaptureSource = 'manual' | 'animation-frame' | 'animation-stack' | 'imported' | 'snippet' | 'baked-clip' | string;

export interface GestureTransform {
  rotation?: [number, number, number, number];
  position?: [number, number, number];
  scale?: [number, number, number];
  source?: string;
}

export interface GestureKeyframe {
  timeMs: number;
  bones: Record<string, GestureTransform>;
}

export interface GestureAUMapping {
  auId: string;
  node: string;
  boneName?: string;
  channel?: string;
  scale?: number;
  maxDegrees?: number;
  maxUnits?: number;
  side?: 'left' | 'right';
  name?: string;
}

export interface GestureSnapshot {
  id: string;
  version?: number;
  name: string;
  description?: string;
  textRepresentation?: string;
  sourceText?: string;
  scope?: GestureScope;
  emoji?: string;
  tags?: string[];
  createdAt?: number;
  updatedAt?: number;
  captureSource?: GestureCaptureSource;
  durationMs?: number;
  easing?: string;
  loop?: boolean;
  priority?: number;
  returnToBase?: boolean;
  affectedBones?: string[];
  affectedAUs?: string[];
  auMappings?: GestureAUMapping[];
  bones?: Record<string, GestureTransform>;
  keyframes?: GestureKeyframe[];
  metadata?: Record<string, unknown>;
}

export interface GestureConfig {
  enabled?: boolean;
  intensity?: number;
  priority?: number;
  defaultDurationMs?: number;
  cooldownMs?: number;
  rampRatio?: number;
  holdRatio?: number;
  returnToBase?: boolean;
  replaceActive?: boolean;
  maxActive?: number;
  gestures?: Record<string, GestureSnapshot>;
  characterGestures?: Record<string, GestureSnapshot>;
  gestureLibrary?: Record<string, GestureSnapshot>;
  emojiMappings?: Record<string, string>;
  gestureEmojiMappings?: Record<string, string>;
}

export interface GestureActiveSnippet {
  name: string;
  gestureId: string;
  gestureName?: string;
  emoji?: string;
  scope?: GestureScope;
  affectedBones?: string[];
  maxTime: number;
  scheduledAt: number;
}

export interface GestureState {
  agency: 'gesture';
  gestures: string[];
  gestureCount: number;
  emojiCount: number;
  activeSnippets: Record<string, GestureActiveSnippet>;
  scheduledCount: number;
  removedCount: number;
  config: Required<Omit<GestureConfig, 'gestures' | 'characterGestures' | 'emojiMappings' | 'gestureEmojiMappings'>>;
  lastEvent: null | Record<string, unknown>;
}

export type GestureDispatch =
  | { type: 'configure'; config: GestureConfig }
  | {
      type: 'loadGestures';
      gestures?: Record<string, GestureSnapshot>;
      characterGestures?: Record<string, GestureSnapshot>;
      gestureLibrary?: Record<string, GestureSnapshot>;
      emojiMappings?: Record<string, string>;
      gestureEmojiMappings?: Record<string, string>;
    }
  | { type: 'playGesture'; gestureId: string; name?: string; intensity?: number }
  | { type: 'playEmoji'; emoji: string; name?: string; intensity?: number }
  | { type: 'stopGesture'; gestureId: string }
  | { type: 'stopAll' }
  | { type: 'reset' };

export interface ConversationConfig {
  autoRespond?: boolean;
  maxHistory?: number;
  responseSource?: string;
  ttsAgency?: string;
  interruptionMode?: string;
}

export interface ConversationState {
  agency: 'conversation';
  status: string;
  started: boolean;
  turnId: string | null;
  history: Array<Record<string, unknown>>;
  pendingResponse: Record<string, unknown> | null;
  lastUserText: string | null;
  lastAgentText: string | null;
  interrupted: boolean;
  userUtteranceCount: number;
  agentUtteranceCount: number;
  responseRequestCount: number;
  ttsRequestCount: number;
  cancelCount: number;
  config: Required<ConversationConfig>;
  lastEvent: null | Record<string, unknown>;
}

export type ConversationDispatch =
  | { type: 'configure'; config: ConversationConfig }
  | { type: 'start' | 'stop' | 'reset' | 'cancel' | 'interrupt'; reason?: string }
  | { type: 'transcript.final' | 'transcriptFinal' | 'userUtterance'; text?: string; transcript?: string; utterance?: string; responseText?: string; source?: string }
  | { type: 'agentUtterance' | 'responseReady'; text?: string; responseText?: string; agentText?: string; utterance?: string; requestId?: string; turnId?: string; source?: string }
  | { type: 'tts.status'; status?: string };

export interface TranscriptionConfig {
  provider?: string;
  lang?: string;
  continuous?: boolean;
  interimResults?: boolean;
  maxAlternatives?: number;
  maxRetries?: number;
  retryDelayMs?: number;
  minConfidence?: number;
  agentFilteringEnabled?: boolean;
  interruptDetectionEnabled?: boolean;
}

export interface TranscriptionState {
  agency: 'transcription';
  status: string;
  active: boolean;
  sessionId: string | null;
  sequence: number;
  currentTranscript: string | null;
  isFinal: boolean;
  lastPartial: Record<string, unknown> | null;
  lastFinal: Record<string, unknown> | null;
  lastError: string | null;
  agentSpeaking: boolean;
  agentSpeechStatus: string;
  retryCount: number;
  partialCount: number;
  finalCount: number;
  errorCount: number;
  ignoredCount: number;
  interruptionCount: number;
  config: Required<TranscriptionConfig>;
  lastEvent: null | Record<string, unknown>;
}

export type TranscriptionDispatch =
  | { type: 'configure'; config: TranscriptionConfig }
  | { type: 'start' | 'stop' | 'cancel' | 'reset'; reason?: string }
  | { type: 'providerPartial' | 'partialTranscript' | 'providerFinal' | 'finalTranscript'; text?: string; transcript?: string; confidence?: number; source?: string; speaker?: string }
  | { type: 'providerError'; message?: string; error?: string }
  | { type: 'tts.status'; status?: string; speaking?: boolean; state?: Record<string, unknown> }
  | { type: 'ttsSpeechStarted' | 'ttsSpeechStopped' | 'ttsSpeechEnded'; status?: string; speaking?: boolean };

export interface HairColorConfig {
  name?: string;
  baseColor?: string;
  emissive?: string;
  emissiveIntensity?: number;
}

export interface HairPhysicsConfig {
  enabled?: boolean;
  stiffness?: number;
  damping?: number;
  inertia?: number;
  gravity?: number;
  responseScale?: number;
  idleSwayAmount?: number;
  idleSwaySpeed?: number;
  windStrength?: number;
  windDirectionX?: number;
  windDirectionZ?: number;
  windTurbulence?: number;
  windFrequency?: number;
  idleClipDurationMs?: number;
  impulseClipDurationMs?: number;
  coalesceMs?: number;
}

export interface HairObjectRef {
  name: string;
  isEyebrow?: boolean;
  isMesh?: boolean;
}

export interface HairConfig {
  physics?: HairPhysicsConfig;
  hairColor?: HairColorConfig | string;
  eyebrowColor?: HairColorConfig | string;
  showOutline?: boolean;
  outlineColor?: string;
  outlineOpacity?: number;
  objects?: HairObjectRef[];
  parts?: Record<string, unknown>;
}

export interface HairState {
  agency: 'hair';
  status: string;
  hairColor: Required<HairColorConfig>;
  eyebrowColor: Required<HairColorConfig>;
  showOutline: boolean;
  outlineColor: string;
  outlineOpacity: number;
  objects: HairObjectRef[];
  parts: Record<string, unknown>;
  physics: Required<HairPhysicsConfig>;
  lastPlan: null | Record<string, unknown>;
  lastMotion: null | Record<string, unknown>;
  lastRuntimeRequest: null | Record<string, unknown>;
  planCount: number;
  motionCount: number;
  runtimeRequestCount: number;
  resetCount: number;
  config: Required<Omit<HairConfig, 'hairColor' | 'eyebrowColor' | 'physics' | 'objects' | 'parts'>> & {
    hairColor: Required<HairColorConfig>;
    eyebrowColor: Required<HairColorConfig>;
    physics: Required<HairPhysicsConfig>;
    objects: HairObjectRef[];
    parts: Record<string, unknown>;
  };
  lastEvent: null | Record<string, unknown>;
}

export type HairDispatch =
  | { type: 'configure'; config: HairConfig }
  | { type: 'registerObjects'; objects: HairObjectRef[] }
  | { type: 'setHairColor' | 'setBaseColor' | 'setHairBaseColor'; color?: HairColorConfig | string; value?: HairColorConfig | string; hairColor?: HairColorConfig | string; baseColor?: string }
  | { type: 'setEyebrowColor'; color?: HairColorConfig | string; value?: HairColorConfig | string; eyebrowColor?: HairColorConfig | string }
  | { type: 'setOutline'; show?: boolean; color?: string; opacity?: number }
  | { type: 'configurePhysics' | 'updatePhysicsConfig'; physics?: HairPhysicsConfig; config?: HairPhysicsConfig; value?: HairPhysicsConfig }
  | { type: 'setPhysicsEnabled'; enabled: boolean }
  | { type: 'motionFact' | 'environmentFact'; velocity?: GazeTarget; delta?: GazeTarget; motion?: Record<string, unknown>; facts?: Record<string, unknown>; [key: string]: unknown }
  | { type: 'reset' };

export interface CameraContextConfig {
  coalesceMs?: number;
  staleAfterMs?: number;
  epsilon?: number;
  yawWeight?: number;
  pitchWeight?: number;
}

export interface CameraContextState {
  agency: 'cameraContext';
  status: string;
  cameraPosition: Record<string, number> | null;
  targetPosition: Record<string, number> | null;
  modelQuaternion: Record<string, number>;
  relativeOffset: Required<GazeTarget>;
  lastUpdatedAt: number | null;
  lastPublishedAt: number | null;
  lastFact: Record<string, unknown> | null;
  lastPlan: Record<string, unknown> | null;
  lastInvalidation: Record<string, unknown> | null;
  stale: boolean;
  invalidated: boolean;
  updateCount: number;
  publishedCount: number;
  config: Required<CameraContextConfig>;
}

export type CameraContextDispatch =
  | { type: 'configure'; config: CameraContextConfig }
  | { type: 'updateCamera'; facts?: Record<string, unknown>; cameraPosition?: Record<string, number>; targetPosition?: Record<string, number>; modelQuaternion?: Record<string, number>; source?: string }
  | { type: 'publishCameraFacts' }
  | { type: 'invalidateStale'; reason?: string }
  | { type: 'reset' };

export interface PolymerStream<TEvent> {
  subscribe(listener: (event: TEvent) => void): () => void;
}

export interface PolymerInputStream<TCommand> extends PolymerStream<{ type: 'command'; agency: string; command?: TCommand; message?: unknown }> {
  write(command: TCommand): void;
}

/**
 * Reserved compatibility stream.
 *
 * Polymer agencies coordinate through typed domain events. No generic public
 * effect events are emitted today, so consumers should subscribe to events
 * unless a future agency explicitly documents a compatibility event here.
 */
export type PolymerEffectEvent = never;

export type PolymerCommandEvent = PolymerEffectEvent;

export type PolymerDomainEvent =
  | {
      type: 'blinkConfigChanged';
      agency: 'blink';
      state: BlinkState;
    }
  | {
      type: 'animation.requestScheduleSnippet';
      agency: 'blink' | 'lipSync' | 'gesture' | 'prosodic' | 'eyeHeadTracking';
      requestId: string;
      snippet: PolymerAnimationSnippet;
      options: { autoPlay?: boolean; [key: string]: unknown };
      effectors?: string[];
      queueIndex?: number;
      queuedAt?: number;
    }
  | {
      type: 'animation.requestRemoveSnippet';
      agency: 'lipSync' | 'gesture' | 'prosodic' | 'eyeHeadTracking';
      requestId: string;
      name: string;
      reason: string;
      queueIndex?: number;
      queuedAt?: number;
    }
  | {
      type: 'animation.requestSeekSnippet';
      agency: 'lipSync';
      requestId: string;
      name: string;
      offsetSec: number;
      reason: string;
      queueIndex?: number;
      queuedAt?: number;
    }
  | {
      type: 'animationSnippetScheduled';
      agency: 'animation';
      sourceAgency: string;
      name: string;
      snippet: PolymerAnimationSnippet;
      options: { autoPlay?: boolean; sourceAgency?: string; [key: string]: unknown };
      requestedAt: number;
    }
  | {
      type: 'animationSnippetStarted';
      agency: 'animation';
      sourceAgency: string;
      name: string;
    }
  | {
      type: 'animationSnippetSeeked';
      agency: 'animation';
      sourceAgency: string;
      name: string;
      offsetSec: number;
      seekedAt: number;
    }
  | {
      type: 'animationSnippetUpdated';
      agency: 'animation';
      sourceAgency: string;
      name: string;
      params: Record<string, unknown>;
      updatedAt: number;
    }
  | {
      type: 'animationSnippetRemoved';
      agency: 'animation';
      sourceAgency: string;
      reason: string;
      name: string;
      removedAt: number;
    }
  | {
      type: 'blinkPlanned';
      agency: 'blink';
      plan: Record<string, unknown>;
      snippetName: string;
      nextDelayMs: number | null;
    }
  | { type: 'signal'; agency: 'blink'; signal: 'blink-fast'; plan: Record<string, unknown> }
  | { type: 'gaze.status'; agency: 'gaze'; status: string; mode: string; active: boolean; enabled: boolean; reason?: string; at: number }
  | { type: 'gaze.targetReceived'; agency: 'gaze'; requestId: string; rawTarget: Required<GazeTarget>; source?: string; label?: string; at: number }
  | {
      type: 'gaze.targetPlanned';
      agency: 'gaze';
      requestId: string;
      rawTarget: Required<GazeTarget>;
      target: Required<GazeTarget>;
      delta: number;
      eyeDurationMs: number;
      headDurationMs: number;
      at: number;
    }
  | {
      type: 'gaze.targetIgnored';
      agency: 'gaze';
      requestId: string;
      rawTarget: Required<GazeTarget>;
      target: Required<GazeTarget>;
      reason: 'disabled' | 'min-delta' | string;
      ignoredAt: number;
    }
  | GazeLookRequest
  | GazeResetRequest
  | GazeCancelRequest
  | { type: 'camera.status'; agency: 'cameraContext'; status: string; stale: boolean; reason?: string; at: number }
  | {
      type: 'camera.fact';
      agency: 'cameraContext';
      kind: 'camera.relative';
      sequence: number;
      source: string;
      observedAt: number;
      publishedAt: number;
      cameraPosition: Record<string, number>;
      targetPosition: Record<string, number>;
      modelQuaternion: Record<string, number>;
      relativeOffset: Required<GazeTarget>;
      stale: false;
    }
  | {
      type: 'camera.stale';
      agency: 'cameraContext';
      status: 'stale';
      reason: string;
      invalidatedAt: number;
      lastPublishedAt?: number;
      lastObservedAt?: number;
      lastRelativeOffset?: Required<GazeTarget>;
    }
  | {
      type: 'eyeHeadTracking.status';
      agency: 'eyeHeadTracking';
      status: string;
      enabled: boolean;
      activeSnippetNames: string[];
      reason?: string;
      at: number;
    }
  | {
      type: 'eyeHeadTracking.requestIgnored';
      agency: 'eyeHeadTracking';
      requestId: string;
      reason: string;
      target?: Required<GazeTarget>;
      ignoredAt: number;
    }
  | {
      type: 'eyeHeadTracking.cancelled';
      agency: 'eyeHeadTracking';
      requestId: string;
      reason: string;
      at: number;
    }
  | { type: 'lipSyncPlanCreated'; agency: 'lipSync'; plan: Record<string, unknown> }
  | { type: 'lipSyncConfigChanged'; agency: 'lipSync'; state: LipSyncState }
  | {
      type: 'lipSyncTimelineStarted';
      agency: 'lipSync';
      name: string;
      source: string;
      text?: string;
      visemeCount: number;
      maxTime: number;
      startedAt: number;
    }
  | { type: 'lipSyncTimelineStopped'; agency: 'lipSync'; reason: string; stoppedAt: number }
  | { type: 'lipSyncWordBoundary'; agency: 'lipSync'; word: string; wordIndex: number; observedAt: number }
  | { type: 'lipSyncWordTimingsUpdated'; agency: 'lipSync'; count: number; updatedAt: number }
  | { type: 'lipSyncAudioStarted'; agency: 'lipSync'; name?: string; audioTimeSec: number; observedAt: number }
  | { type: 'lipSyncAudioTime'; agency: 'lipSync'; name?: string; audioTimeSec: number; observedAt: number }
  | {
      type: 'lipSyncSyncDrift';
      agency: 'lipSync';
      name: string;
      word: string;
      wordIndex: number;
      expectedSec: number;
      observedSec: number;
      driftSec: number;
      targetSec: number;
      correctedAt: number;
    }
  | { type: 'ttsStatusChanged'; agency: 'tts'; state: TTSState }
  | { type: 'ttsPlanCreated'; agency: 'tts'; plan: Record<string, unknown> }
  | {
      type: 'ttsVoicesLoaded';
      agency: 'tts';
      engine: 'webSpeech' | 'azure' | string;
      voices: TTSVoice[];
      status?: 'checking' | 'ready' | 'error' | string;
      message?: string;
    }
  | { type: 'ttsSpeechStarted'; agency: 'tts'; engine: string; name: string; startedAt: number }
  | { type: 'ttsSpeechStopped'; agency: 'tts'; reason: string; stoppedAt: number }
  | { type: 'ttsSpeechEnded'; agency: 'tts'; endedAt: number }
  | {
      type: 'ttsWordBoundary';
      agency: 'tts';
      word: string;
      wordIndex: number;
      observedElapsedSec?: number;
      hostElapsedSec?: number;
    }
  | { type: 'lipSync.command'; agency: 'tts'; command: LipSyncDispatch }
  | { type: 'conversation.status'; agency: 'conversation'; status: string; turnId?: string; reason?: string; at: number }
  | { type: 'conversation.userUtterance'; agency: 'conversation'; text: string; source: string; turnId?: string; at: number }
  | { type: 'conversation.requestResponse'; agency: 'conversation'; targetAgency: string; requestId: string; text: string; source: string; turnId?: string; history: Array<Record<string, unknown>>; requestedAt: number }
  | { type: 'conversation.agentUtterance'; agency: 'conversation'; text: string; at: number }
  | { type: 'conversation.cancelRequested'; agency: 'conversation'; targetAgency: string; reason: string; turnId?: string; at: number }
  | { type: 'conversation.ignored'; agency: 'conversation'; reason: string; requestId?: string; turnId?: string; at: number }
  | { type: 'tts.requestSpeak'; agency: 'conversation'; targetAgency: string; requestId: string; text: string; source: string; turnId?: string; command: TTSDispatch; requestedAt: number }
  | { type: 'transcription.status'; agency: 'transcription'; status: string; sessionId?: string; reason?: string; at: number }
  | { type: 'transcription.requestProvider'; agency: 'transcription'; targetAgency: 'transcription-provider'; action: string; provider: string; sessionId?: string; requestedAt?: number; [key: string]: unknown }
  | { type: 'transcription.partial' | 'transcription.final'; agency: 'transcription'; targetAgency: 'conversation'; text: string; confidence: number; isFinal: boolean; sequence: number; source: string; sessionId?: string; at: number }
  | { type: 'transcription.interruption'; agency: 'transcription'; targetAgency: 'conversation'; text: string; confidence: number; source: string; sessionId?: string; at: number }
  | { type: 'transcription.ttsStatus'; agency: 'transcription'; sourceAgency: 'tts'; status: string; speaking: boolean; ttsEventType?: string; at: number }
  | { type: 'transcription.ignored'; agency: 'transcription'; reason: string; commandType?: string; at: number }
  | { type: 'hair.status'; agency: 'hair'; status: string; reason?: string; at: number }
  | { type: 'hairPlanCreated'; agency: 'hair'; plan: Record<string, unknown> }
  | { type: 'hair.requestRuntime'; agency: 'hair'; targetAgency: 'hair-runtime'; action: 'applyState' | 'applyMotion' | 'reset' | string; requestId: string; queueIndex: number; queuedAt: number; publishedAt: number; requestedAt: number; [key: string]: unknown }
  | { type: 'gestureConfigChanged'; agency: 'gesture'; state: GestureState }
  | { type: 'gestureLibraryUpdated'; agency: 'gesture'; gestureCount: number; emojiCount: number; updatedAt: number }
  | { type: 'gesturePlanCreated'; agency: 'gesture'; plan: Record<string, unknown> }
  | {
      type: 'gestureScheduled';
      agency: 'gesture';
      gestureId: string;
      gestureName: string;
      emoji?: string;
      scope?: GestureScope;
      affectedBones?: string[];
      name: string;
      scheduledAt: number;
    }
  | { type: 'gestureRemoved'; agency: 'gesture'; name: string; reason: string; removedAt: number }
  | { type: 'prosodicPlanCreated'; agency: 'prosodic'; plan: Record<string, unknown> }
  | { type: 'prosodicConfigChanged'; agency: 'prosodic'; state: ProsodicState }
  | { type: 'prosodicSpeechStarted'; agency: 'prosodic'; name?: string; startedAt: number }
  | { type: 'prosodicWordBoundary'; agency: 'prosodic'; word?: string; wordIndex: number; observedAt: number }
  | {
      type: 'prosodicGestureScheduled';
      agency: 'prosodic';
      gesture: string;
      name: string;
      word?: string;
      wordIndex?: number;
      scheduledAt: number;
    }
  | { type: 'prosodicStopped'; agency: 'prosodic'; reason: string; stoppedAt: number }
  | { type: string; agency: 'conversation' | 'transcription' | 'hair' | 'cameraContext'; [key: string]: unknown }
  | {
      type: 'ready';
      agency:
        | 'character'
        | 'blink'
        | 'animation'
        | 'gaze'
        | 'eyeHeadTracking'
        | 'lipSync'
        | 'tts'
        | 'gesture'
        | 'prosodic'
        | 'conversation'
        | 'transcription'
        | 'hair'
        | 'cameraContext';
    }
  | { type: 'error'; agency: string; message: string };

export type PolymerStatusEvent = PolymerDomainEvent;

export interface BlinkAgency {
  input: PolymerInputStream<BlinkDispatch>;
  events: PolymerStream<PolymerDomainEvent>;
  effects: PolymerStream<PolymerEffectEvent>;
  dispatch(command: BlinkDispatch): void;
  snapshot(): BlinkState;
  subscribeInput(listener: (event: { type: 'command'; agency: 'blink'; command: BlinkDispatch }) => void): () => void;
  subscribeEvents(listener: (event: PolymerDomainEvent) => void): () => void;
  subscribeEffects(listener: (event: PolymerEffectEvent) => void): () => void;
  /** Compatibility alias for events. Prefer subscribeEvents. */
  subscribe(listener: (event: PolymerStatusEvent) => void): () => void;
  /** Compatibility alias for events. Prefer subscribeEvents. */
  subscribeStatus(listener: (event: PolymerStatusEvent) => void): () => void;
  /** Compatibility alias for effects. Prefer subscribeEffects. */
  subscribeCommands(listener: (event: PolymerEffectEvent) => void): () => void;
  enable(): void;
  disable(): void;
  triggerBlink(options?: BlinkTriggerOptions): void;
  setFrequency(value: number): void;
  setDuration(value: number): void;
  setIntensity(value: number): void;
  setRandomness(value: number): void;
  setLeftEyeIntensity(value: number | null): void;
  setRightEyeIntensity(value: number | null): void;
  setBurstEnabled(value: boolean): void;
  setBurstFrequency(value: number): void;
  setBurstCount(value: number): void;
  setBurstGap(value: number): void;
  reset(): void;
  dispose(): void;
}

export interface AnimationAgency {
  input: PolymerInputStream<AnimationDispatch>;
  events: PolymerStream<PolymerDomainEvent>;
  effects: PolymerStream<PolymerEffectEvent>;
  dispatch(command: AnimationDispatch): void;
  snapshot(): AnimationState;
  subscribeInput(listener: (event: { type: 'command'; agency: 'animation'; command: AnimationDispatch }) => void): () => void;
  subscribeEvents(listener: (event: PolymerDomainEvent) => void): () => void;
  subscribeEffects(listener: (event: PolymerEffectEvent) => void): () => void;
  /** Compatibility alias for events. Prefer subscribeEvents. */
  subscribe(listener: (event: PolymerStatusEvent) => void): () => void;
  /** Compatibility alias for events. Prefer subscribeEvents. */
  subscribeStatus(listener: (event: PolymerStatusEvent) => void): () => void;
  /** Compatibility alias for effects. Prefer subscribeEffects. */
  subscribeCommands(listener: (event: PolymerEffectEvent) => void): () => void;
  scheduleSnippet(snippet: PolymerAnimationSnippet, options?: { autoPlay?: boolean; [key: string]: unknown }): void;
  seekSnippet(name: string, offsetSec: number): void;
  updateSnippet(name: string, params: Record<string, unknown>): void;
  removeSnippet(name: string): void;
  dispose(): void;
}

export interface GazeAgency {
  input: PolymerInputStream<GazeDispatch>;
  events: PolymerStream<PolymerDomainEvent>;
  effects: PolymerStream<PolymerEffectEvent>;
  dispatch(command: GazeDispatch): void;
  snapshot(): GazeState;
  subscribeInput(listener: (event: { type: 'command'; agency: 'gaze'; command: GazeDispatch }) => void): () => void;
  subscribeEvents(listener: (event: PolymerDomainEvent) => void): () => void;
  subscribeEffects(listener: (event: PolymerEffectEvent) => void): () => void;
  /** Compatibility alias for events. Prefer subscribeEvents. */
  subscribe(listener: (event: PolymerStatusEvent) => void): () => void;
  /** Compatibility alias for events. Prefer subscribeEvents. */
  subscribeStatus(listener: (event: PolymerStatusEvent) => void): () => void;
  /** Compatibility alias for effects. Prefer subscribeEffects. */
  subscribeCommands(listener: (event: PolymerEffectEvent) => void): () => void;
  setTarget(target: GazeTarget, options?: GazeApplyOptions): void;
  focusTarget(target: GazeTarget): void;
  configure(config: GazeConfig): void;
  setMode(mode: string): void;
  setActive(active: boolean): void;
  enable(): void;
  disable(): void;
  reset(durationMs?: number): void;
  cancel(reason?: string): void;
  flush(): void;
  queue(): Array<Record<string, unknown>>;
  dispose(): void;
}

export interface EyeHeadTrackingAgency {
  input: PolymerInputStream<EyeHeadTrackingDispatch>;
  events: PolymerStream<PolymerDomainEvent>;
  effects: PolymerStream<PolymerEffectEvent>;
  dispatch(command: EyeHeadTrackingDispatch): void;
  snapshot(): EyeHeadTrackingState;
  subscribeInput(listener: (event: { type: 'command'; agency: 'eyeHeadTracking'; command: EyeHeadTrackingDispatch }) => void): () => void;
  subscribeEvents(listener: (event: PolymerDomainEvent) => void): () => void;
  subscribeEffects(listener: (event: PolymerEffectEvent) => void): () => void;
  /** Compatibility alias for events. Prefer subscribeEvents. */
  subscribe(listener: (event: PolymerStatusEvent) => void): () => void;
  /** Compatibility alias for events. Prefer subscribeEvents. */
  subscribeStatus(listener: (event: PolymerStatusEvent) => void): () => void;
  /** Compatibility alias for effects. Prefer subscribeEffects. */
  subscribeCommands(listener: (event: PolymerEffectEvent) => void): () => void;
  setTarget(target: GazeTarget): void;
  configure(config: EyeHeadTrackingConfig): void;
  enable(): void;
  disable(): void;
  reset(durationMs?: number): void;
  cancel(reason?: string): void;
  flush(): void;
  queue(): Array<Record<string, unknown>>;
  dispose(): void;
}

export interface LipSyncAgency {
  input: PolymerInputStream<LipSyncDispatch>;
  events: PolymerStream<PolymerDomainEvent>;
  effects: PolymerStream<PolymerEffectEvent>;
  dispatch(command: LipSyncDispatch): void;
  snapshot(): LipSyncState;
  schedulerQueue(): LipSyncSchedulerQueueEntry[];
  subscribeInput(listener: (event: { type: 'command'; agency: 'lipSync'; command: LipSyncDispatch }) => void): () => void;
  subscribeEvents(listener: (event: PolymerDomainEvent) => void): () => void;
  subscribeEffects(listener: (event: PolymerEffectEvent) => void): () => void;
  /** Compatibility alias for events. Prefer subscribeEvents. */
  subscribe(listener: (event: PolymerStatusEvent) => void): () => void;
  /** Compatibility alias for events. Prefer subscribeEvents. */
  subscribeStatus(listener: (event: PolymerStatusEvent) => void): () => void;
  /** Compatibility alias for effects. Prefer subscribeEffects. */
  subscribeCommands(listener: (event: PolymerEffectEvent) => void): () => void;
  configure(config: LipSyncConfig): void;
  startText(text: string): void;
  startTimeline(timeline: LipSyncTimeline): void;
  processAzureVisemes(visemes: AzureVisemeEvent[], totalDurationMs?: number): void;
  wordBoundary(word: string, wordIndex?: number, observedElapsedSec?: number, hostElapsedSec?: number): void;
  audioStarted(audioTimeSec?: number): void;
  audioTime(audioTimeSec: number): void;
  updateWordTimings(wordTimings: LipSyncWordTiming[]): void;
  stop(): void;
  reset(): void;
  dispose(): void;
}

export interface TTSAgency {
  input: PolymerInputStream<TTSDispatch>;
  events: PolymerStream<PolymerDomainEvent>;
  effects: PolymerStream<PolymerEffectEvent>;
  dispatch(command: TTSDispatch): void;
  snapshot(): TTSState;
  schedulerQueue(): TTSSchedulerQueueEntry[];
  subscribeInput(listener: (event: { type: 'command'; agency: 'tts'; command: TTSDispatch }) => void): () => void;
  subscribeEvents(listener: (event: PolymerDomainEvent) => void): () => void;
  subscribeEffects(listener: (event: PolymerEffectEvent) => void): () => void;
  /** Compatibility alias for events. Prefer subscribeEvents. */
  subscribe(listener: (event: PolymerStatusEvent) => void): () => void;
  /** Compatibility alias for events. Prefer subscribeEvents. */
  subscribeStatus(listener: (event: PolymerStatusEvent) => void): () => void;
  /** Compatibility alias for effects. Prefer subscribeEffects. */
  subscribeCommands(listener: (event: PolymerEffectEvent) => void): () => void;
  configure(config: TTSConfig): void;
  loadVoices(engine?: 'webSpeech' | 'azure' | string): void;
  speak(text: string): void;
  stop(): void;
  reset(): void;
  dispose(): void;
}

export interface GestureAgency {
  input: PolymerInputStream<GestureDispatch>;
  events: PolymerStream<PolymerDomainEvent>;
  effects: PolymerStream<PolymerEffectEvent>;
  dispatch(command: GestureDispatch): void;
  snapshot(): GestureState;
  schedulerQueue(): Array<Record<string, unknown>>;
  subscribeInput(listener: (event: { type: 'command'; agency: 'gesture'; command: GestureDispatch }) => void): () => void;
  subscribeEvents(listener: (event: PolymerDomainEvent) => void): () => void;
  subscribeEffects(listener: (event: PolymerEffectEvent) => void): () => void;
  /** Compatibility alias for events. Prefer subscribeEvents. */
  subscribe(listener: (event: PolymerStatusEvent) => void): () => void;
  /** Compatibility alias for events. Prefer subscribeEvents. */
  subscribeStatus(listener: (event: PolymerStatusEvent) => void): () => void;
  /** Compatibility alias for effects. Prefer subscribeEffects. */
  subscribeCommands(listener: (event: PolymerEffectEvent) => void): () => void;
  configure(config: GestureConfig): void;
  loadGestures(gestures: Record<string, GestureSnapshot>, emojiMappings?: Record<string, string>): void;
  playGesture(gestureId: string): void;
  playEmoji(emoji: string): void;
  stopGesture(gestureId: string): void;
  stopAll(): void;
  reset(): void;
  dispose(): void;
}

export interface ProsodicAgency {
  input: PolymerInputStream<ProsodicDispatch>;
  events: PolymerStream<PolymerDomainEvent>;
  effects: PolymerStream<PolymerEffectEvent>;
  dispatch(command: ProsodicDispatch): void;
  snapshot(): ProsodicState;
  schedulerQueue(): ProsodicSchedulerQueueEntry[];
  subscribeInput(listener: (event: { type: 'command'; agency: 'prosodic'; command: ProsodicDispatch }) => void): () => void;
  subscribeEvents(listener: (event: PolymerDomainEvent) => void): () => void;
  subscribeEffects(listener: (event: PolymerEffectEvent) => void): () => void;
  /** Compatibility alias for events. Prefer subscribeEvents. */
  subscribe(listener: (event: PolymerStatusEvent) => void): () => void;
  /** Compatibility alias for events. Prefer subscribeEvents. */
  subscribeStatus(listener: (event: PolymerStatusEvent) => void): () => void;
  /** Compatibility alias for effects. Prefer subscribeEffects. */
  subscribeCommands(listener: (event: PolymerEffectEvent) => void): () => void;
  configure(config: ProsodicConfig): void;
  speechStarted(name?: string): void;
  wordBoundary(word: string, wordIndex?: number): void;
  blinkFast(): void;
  stop(): void;
  reset(): void;
  dispose(): void;
}

export interface ConversationAgency {
  input: PolymerInputStream<ConversationDispatch>;
  events: PolymerStream<PolymerDomainEvent>;
  effects: PolymerStream<PolymerEffectEvent>;
  dispatch(command: ConversationDispatch): void;
  snapshot(): ConversationState;
  subscribeInput(listener: (event: { type: 'command'; agency: 'conversation'; command: ConversationDispatch }) => void): () => void;
  subscribeEvents(listener: (event: PolymerDomainEvent) => void): () => void;
  subscribeEffects(listener: (event: PolymerEffectEvent) => void): () => void;
  subscribe(listener: (event: PolymerStatusEvent) => void): () => void;
  subscribeStatus(listener: (event: PolymerStatusEvent) => void): () => void;
  subscribeCommands(listener: (event: PolymerEffectEvent) => void): () => void;
  start(): void;
  stop(): void;
  reset(): void;
  interrupt(reason?: string): void;
  setAutoRespond(value: boolean): void;
  queue(): Array<Record<string, unknown>>;
  dispose(): void;
}

export interface TranscriptionAgency {
  input: PolymerInputStream<TranscriptionDispatch>;
  events: PolymerStream<PolymerDomainEvent>;
  effects: PolymerStream<PolymerEffectEvent>;
  dispatch(command: TranscriptionDispatch): void;
  snapshot(): TranscriptionState;
  subscribeInput(listener: (event: { type: 'command'; agency: 'transcription'; command: TranscriptionDispatch }) => void): () => void;
  subscribeEvents(listener: (event: PolymerDomainEvent) => void): () => void;
  subscribeEffects(listener: (event: PolymerEffectEvent) => void): () => void;
  subscribe(listener: (event: PolymerStatusEvent) => void): () => void;
  subscribeStatus(listener: (event: PolymerStatusEvent) => void): () => void;
  subscribeCommands(listener: (event: PolymerEffectEvent) => void): () => void;
  start(): void;
  stop(): void;
  reset(): void;
  queue(): Array<Record<string, unknown>>;
  dispose(): void;
}

export interface HairAgency {
  input: PolymerInputStream<HairDispatch>;
  events: PolymerStream<PolymerDomainEvent>;
  effects: PolymerStream<PolymerEffectEvent>;
  dispatch(command: HairDispatch): void;
  snapshot(): HairState;
  subscribeInput(listener: (event: { type: 'command'; agency: 'hair'; command: HairDispatch }) => void): () => void;
  subscribeEvents(listener: (event: PolymerDomainEvent) => void): () => void;
  subscribeEffects(listener: (event: PolymerEffectEvent) => void): () => void;
  subscribe(listener: (event: PolymerStatusEvent) => void): () => void;
  subscribeStatus(listener: (event: PolymerStatusEvent) => void): () => void;
  subscribeCommands(listener: (event: PolymerEffectEvent) => void): () => void;
  reset(): void;
  queue(): Array<Record<string, unknown>>;
  dispose(): void;
}

export interface CameraContextAgency {
  input: PolymerInputStream<CameraContextDispatch>;
  events: PolymerStream<PolymerDomainEvent>;
  effects: PolymerStream<PolymerEffectEvent>;
  dispatch(command: CameraContextDispatch): void;
  snapshot(): CameraContextState;
  subscribeInput(listener: (event: { type: 'command'; agency: 'cameraContext'; command: CameraContextDispatch }) => void): () => void;
  subscribeEvents(listener: (event: PolymerDomainEvent) => void): () => void;
  subscribeEffects(listener: (event: PolymerEffectEvent) => void): () => void;
  subscribe(listener: (event: PolymerStatusEvent) => void): () => void;
  subscribeStatus(listener: (event: PolymerStatusEvent) => void): () => void;
  subscribeCommands(listener: (event: PolymerEffectEvent) => void): () => void;
  updateCamera(facts: Record<string, unknown>): void;
  invalidateStale(reason?: string): void;
  reset(): void;
  queue(): Array<Record<string, unknown>>;
  dispose(): void;
}

export interface CharacterAgencySnapshot {
  blink: BlinkState;
  gaze: GazeState;
  eyeHeadTracking: EyeHeadTrackingState;
  gesture: GestureState;
  cameraContext: CameraContextState;
  transcription: TranscriptionState;
  conversation: ConversationState;
  hair: HairState;
  tts: TTSState;
  lipSync: LipSyncState;
  prosodic: ProsodicState;
  conversation: ConversationState;
  transcription: TranscriptionState;
  hair: HairState;
  cameraContext: CameraContextState;
  animation: AnimationState;
}

export type CharacterAgencyDispatch =
  | { agency: 'blink'; command: BlinkDispatch }
  | { agency: 'gaze'; command: GazeDispatch }
  | { agency: 'eyeHeadTracking'; command: EyeHeadTrackingDispatch }
  | { agency: 'gesture'; command: GestureDispatch }
  | { agency: 'cameraContext'; command: CameraContextDispatch }
  | { agency: 'transcription'; command: TranscriptionDispatch }
  | { agency: 'conversation'; command: ConversationDispatch }
  | { agency: 'hair'; command: HairDispatch }
  | { agency: 'tts'; command: TTSDispatch }
  | { agency: 'lipSync'; command: LipSyncDispatch }
  | { agency: 'prosodic'; command: ProsodicDispatch }
  | { agency: 'conversation'; command: ConversationDispatch }
  | { agency: 'transcription'; command: TranscriptionDispatch }
  | { agency: 'hair'; command: HairDispatch }
  | { agency: 'cameraContext'; command: CameraContextDispatch }
  | { agency: 'animation'; command: AnimationDispatch };

export interface CharacterAgencies {
  input: PolymerInputStream<CharacterAgencyDispatch>;
  events: PolymerStream<PolymerDomainEvent>;
  effects: PolymerStream<PolymerEffectEvent>;
  dispatch(message: CharacterAgencyDispatch): void;
  snapshot(): CharacterAgencySnapshot;
  agency(name: 'animation'): AnimationAgency;
  agency(name: 'blink'): BlinkAgency;
  agency(name: 'gaze'): GazeAgency;
  agency(name: 'eyeHeadTracking'): EyeHeadTrackingAgency;
  agency(name: 'gesture'): GestureAgency;
  agency(name: 'cameraContext'): CameraContextAgency;
  agency(name: 'transcription'): TranscriptionAgency;
  agency(name: 'conversation'): ConversationAgency;
  agency(name: 'hair'): HairAgency;
  agency(name: 'tts'): TTSAgency;
  agency(name: 'lipSync'): LipSyncAgency;
  agency(name: 'prosodic'): ProsodicAgency;
  agency(name: 'conversation'): ConversationAgency;
  agency(name: 'transcription'): TranscriptionAgency;
  agency(name: 'hair'): HairAgency;
  agency(name: 'cameraContext'): CameraContextAgency;
  agency(name: string): unknown | null;
  subscribeInput(listener: (event: { type: 'command'; agency: string; message: CharacterAgencyDispatch }) => void): () => void;
  subscribeEvents(listener: (event: PolymerDomainEvent) => void): () => void;
  subscribeEffects(listener: (event: PolymerEffectEvent) => void): () => void;
  /** Compatibility alias for events. Prefer subscribeEvents. */
  subscribe(listener: (event: PolymerStatusEvent) => void): () => void;
  /** Compatibility alias for events. Prefer subscribeEvents. */
  subscribeStatus(listener: (event: PolymerStatusEvent) => void): () => void;
  /** Compatibility alias for effects. Prefer subscribeEffects. */
  subscribeCommands(listener: (event: PolymerEffectEvent) => void): () => void;
  dispose(): void;
}

export function createBlinkAgency(config?: BlinkAgencyConfig): BlinkAgency;
export function createAnimationAgency(config?: AnimationAgencyConfig): AnimationAgency;
export function createGazeAgency(config?: GazeConfig): GazeAgency;
export function createEyeHeadTrackingAgency(config?: EyeHeadTrackingConfig): EyeHeadTrackingAgency;
export function createLipSyncAgency(config?: LipSyncConfig): LipSyncAgency;
export function createTTSAgency(config?: TTSConfig): TTSAgency;
export function createGestureAgency(config?: GestureConfig): GestureAgency;
export function createProsodicAgency(config?: ProsodicConfig): ProsodicAgency;
export function createConversationAgency(config?: ConversationConfig): ConversationAgency;
export function createTranscriptionAgency(config?: TranscriptionConfig): TranscriptionAgency;
export function createHairAgency(config?: HairConfig): HairAgency;
export function createCameraContextAgency(config?: CameraContextConfig): CameraContextAgency;
export function createCharacterAgencies(config?: {
  blink?: BlinkAgencyConfig;
  gaze?: GazeConfig;
  eyeHeadTracking?: EyeHeadTrackingConfig;
  gesture?: GestureConfig;
  cameraContext?: CameraContextConfig;
  transcription?: TranscriptionConfig;
  conversation?: ConversationConfig;
  hair?: HairConfig;
  tts?: TTSConfig;
  lipSync?: LipSyncConfig;
  prosodic?: ProsodicConfig;
  conversation?: ConversationConfig;
  transcription?: TranscriptionConfig;
  hair?: HairConfig;
  cameraContext?: CameraContextConfig;
  animation?: AnimationAgencyConfig;
}): CharacterAgencies;
