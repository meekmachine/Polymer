import type { LoomLargeThree } from '@lovelace_lol/embody';

export {
  Embody,
  BLENDING_MODES,
  THREE_BLENDING_MODES,
  collectMorphMeshes,
  analyzeModel,
  extractModelData,
  getPreset,
  validateMappings,
  LIP_SYNC_TO_BONES,
  extendCharacterConfigWithPreset,
  extractProfileOverrides,
  mergeCharacterRegionsByName,
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
  VISEME_KEYS,
} from '@lovelace_lol/embody';

export type {
  AUInfo,
  AnimationClipInfo,
  AnimationInfo,
  AnnotationLaterality,
  BoneBinding,
  BoneInfo,
  CameraRelativeGazeOffset,
  CameraRelativeGazeOptions,
  CharacterConfig,
  CharacterRegistry,
  CompositeRotation,
  ExpandAnimation,
  ExpandedRegionState,
  FallbackConfig,
  LineConfig,
  LineCurve,
  LineStyle,
  LoomLargeThree,
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
  removedCount: number;
  lastEvent: null | {
    type: 'animationSnippetScheduled' | 'animationSnippetStarted' | 'animationSnippetSeeked' | 'animationSnippetRemoved';
    name: string;
    sourceAgency: string;
    reason?: string;
    offsetSec?: number;
    at: number;
  };
}

export interface AnimationAgencyConfig {
  runtime?: PolymerAnimationRuntime;
  engine?: LoomLargeThree;
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
  | { type: 'lipSync'; id: number }
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
      agency: 'blink' | 'lipSync' | 'prosodic';
      requestId: string;
      snippet: PolymerAnimationSnippet;
      options: { autoPlay?: boolean; [key: string]: unknown };
      effectors?: string[];
      queueIndex?: number;
      queuedAt?: number;
    }
  | {
      type: 'animation.requestRemoveSnippet';
      agency: 'lipSync' | 'prosodic';
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
  | { type: 'ready'; agency: 'character' | 'blink' | 'animation' | 'lipSync' | 'tts' | 'prosodic' }
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
  removeSnippet(name: string): void;
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

export interface CharacterAgencySnapshot {
  blink: BlinkState;
  tts: TTSState;
  lipSync: LipSyncState;
  prosodic: ProsodicState;
  animation: AnimationState;
}

export type CharacterAgencyDispatch =
  | { agency: 'blink'; command: BlinkDispatch }
  | { agency: 'tts'; command: TTSDispatch }
  | { agency: 'lipSync'; command: LipSyncDispatch }
  | { agency: 'prosodic'; command: ProsodicDispatch }
  | { agency: 'animation'; command: AnimationDispatch };

export interface CharacterAgencies {
  input: PolymerInputStream<CharacterAgencyDispatch>;
  events: PolymerStream<PolymerDomainEvent>;
  effects: PolymerStream<PolymerEffectEvent>;
  dispatch(message: CharacterAgencyDispatch): void;
  snapshot(): CharacterAgencySnapshot;
  agency(name: 'animation'): AnimationAgency;
  agency(name: 'blink'): BlinkAgency;
  agency(name: 'tts'): TTSAgency;
  agency(name: 'lipSync'): LipSyncAgency;
  agency(name: 'prosodic'): ProsodicAgency;
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
export function createLipSyncAgency(config?: LipSyncConfig): LipSyncAgency;
export function createTTSAgency(config?: TTSConfig): TTSAgency;
export function createProsodicAgency(config?: ProsodicConfig): ProsodicAgency;
export function createCharacterAgencies(config?: {
  blink?: BlinkAgencyConfig;
  tts?: TTSConfig;
  lipSync?: LipSyncConfig;
  prosodic?: ProsodicConfig;
  animation?: AnimationAgencyConfig;
}): CharacterAgencies;
