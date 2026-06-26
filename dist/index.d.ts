import type { LoomLargeThree } from '@lovelace_lol/loom3';

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

export interface VocalConfig {
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

export interface VocalVisemeEvent {
  visemeId: number;
  /**
   * Optional independent jaw-axis activation for this event. When omitted,
   * Polymer derives a default from the canonical viseme slot. Set to 0 for a
   * lip-only viseme, or scale with VocalConfig.jawScale for JALI-style control.
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

export interface VocalWordTiming {
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

export interface VocalTimeline {
  name?: string;
  text?: string;
  source?: 'text' | 'azure' | 'livekit' | 'webSpeech' | string;
  visemes: VocalVisemeEvent[];
  wordTimings?: VocalWordTiming[];
  durationSec?: number;
}

export interface VocalState {
  agency: 'vocal';
  speaking: boolean;
  currentWord: string | null;
  currentViseme: number | null;
  snippetName: string | null;
  source: string | null;
  text: string | null;
  startTime: number | null;
  maxTime: number;
  wordIndex: number;
  wordTimings: Array<{ word: string; startSec: number; endSec: number }>;
  scheduledCount: number;
  stoppedCount: number;
  syncCorrectionCount: number;
  config: Required<VocalConfig>;
  lastEvent: null | Record<string, unknown>;
}

export type VocalDispatch =
  | { type: 'configure'; config: VocalConfig }
  | {
      type: 'startText';
      text: string;
      name?: string;
      source?: string;
      wordTimings?: VocalWordTiming[];
      durationSec?: number;
      totalDurationMs?: number;
    }
  | { type: 'startTimeline'; timeline: VocalTimeline }
  | {
      type: 'processAzureVisemes';
      visemes: AzureVisemeEvent[];
      totalDurationMs?: number;
      name?: string;
      text?: string;
      source?: string;
      wordTimings?: VocalWordTiming[];
      options?: { wordTimings?: VocalWordTiming[]; visualLeadMs?: number; [key: string]: unknown };
    }
  | { type: 'wordBoundary'; word: string; wordIndex?: number; observedElapsedSec?: number; hostElapsedSec?: number }
  | { type: 'updateWordTimings'; wordTimings: VocalWordTiming[] }
  | { type: 'stop' }
  | { type: 'reset' };

export interface PolymerStream<TEvent> {
  subscribe(listener: (event: TEvent) => void): () => void;
}

export interface PolymerInputStream<TCommand> extends PolymerStream<{ type: 'command'; agency: string; command?: TCommand; message?: unknown }> {
  write(command: TCommand): void;
}

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
      agency: 'blink' | 'vocal';
      requestId: string;
      snippet: PolymerAnimationSnippet;
      options: { autoPlay?: boolean; [key: string]: unknown };
    }
  | {
      type: 'animation.requestRemoveSnippet';
      agency: 'vocal';
      requestId: string;
      name: string;
      reason: string;
    }
  | {
      type: 'animation.requestSeekSnippet';
      agency: 'vocal';
      requestId: string;
      name: string;
      offsetSec: number;
      reason: string;
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
  | { type: 'vocalConfigChanged'; agency: 'vocal'; state: VocalState }
  | {
      type: 'vocalTimelineStarted';
      agency: 'vocal';
      name: string;
      source: string;
      text?: string;
      visemeCount: number;
      maxTime: number;
      startedAt: number;
    }
  | { type: 'vocalTimelineStopped'; agency: 'vocal'; reason: string; stoppedAt: number }
  | { type: 'vocalWordBoundary'; agency: 'vocal'; word: string; wordIndex: number; observedAt: number }
  | { type: 'vocalWordTimingsUpdated'; agency: 'vocal'; count: number; updatedAt: number }
  | {
      type: 'vocalSyncDrift';
      agency: 'vocal';
      name: string;
      word: string;
      wordIndex: number;
      expectedSec: number;
      observedSec: number;
      driftSec: number;
      targetSec: number;
      correctedAt: number;
    }
  | { type: 'ready'; agency: 'character' | 'blink' | 'animation' | 'vocal' }
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

export interface VocalAgency {
  input: PolymerInputStream<VocalDispatch>;
  events: PolymerStream<PolymerDomainEvent>;
  effects: PolymerStream<PolymerEffectEvent>;
  dispatch(command: VocalDispatch): void;
  snapshot(): VocalState;
  subscribeInput(listener: (event: { type: 'command'; agency: 'vocal'; command: VocalDispatch }) => void): () => void;
  subscribeEvents(listener: (event: PolymerDomainEvent) => void): () => void;
  subscribeEffects(listener: (event: PolymerEffectEvent) => void): () => void;
  /** Compatibility alias for events. Prefer subscribeEvents. */
  subscribe(listener: (event: PolymerStatusEvent) => void): () => void;
  /** Compatibility alias for events. Prefer subscribeEvents. */
  subscribeStatus(listener: (event: PolymerStatusEvent) => void): () => void;
  /** Compatibility alias for effects. Prefer subscribeEffects. */
  subscribeCommands(listener: (event: PolymerEffectEvent) => void): () => void;
  configure(config: VocalConfig): void;
  startText(text: string): void;
  startTimeline(timeline: VocalTimeline): void;
  processAzureVisemes(visemes: AzureVisemeEvent[], totalDurationMs?: number): void;
  wordBoundary(word: string, wordIndex?: number, observedElapsedSec?: number): void;
  updateWordTimings(wordTimings: VocalWordTiming[]): void;
  stop(): void;
  reset(): void;
  dispose(): void;
}

export interface CharacterAgencySnapshot {
  blink: BlinkState;
  vocal: VocalState;
  animation: AnimationState;
}

export type CharacterAgencyDispatch =
  | { agency: 'blink'; command: BlinkDispatch }
  | { agency: 'vocal'; command: VocalDispatch }
  | { agency: 'animation'; command: AnimationDispatch };

export interface CharacterAgencies {
  input: PolymerInputStream<CharacterAgencyDispatch>;
  events: PolymerStream<PolymerDomainEvent>;
  effects: PolymerStream<PolymerEffectEvent>;
  dispatch(message: CharacterAgencyDispatch): void;
  snapshot(): CharacterAgencySnapshot;
  agency(name: 'animation'): AnimationAgency;
  agency(name: 'blink'): BlinkAgency;
  agency(name: 'vocal'): VocalAgency;
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
export function createVocalAgency(config?: VocalConfig): VocalAgency;
export function createCharacterAgencies(config?: {
  blink?: BlinkAgencyConfig;
  vocal?: VocalConfig;
  animation?: AnimationAgencyConfig;
}): CharacterAgencies;
