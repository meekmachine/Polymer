import type { LoomLargeThree } from '@lovelace_lol/loom3';
import type { EmbodyAnimationRuntime } from '@lovelace_lol/loom3/cljs';

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
  removedCount: number;
  lastEvent: null | {
    type: 'animationSnippetScheduled' | 'animationSnippetStarted' | 'animationSnippetRemoved';
    name: string;
    sourceAgency: string;
    reason?: string;
    at: number;
  };
}

export interface AnimationAgencyConfig {
  runtime?: EmbodyAnimationRuntime;
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
  | { type: 'removeSnippet'; sourceAgency?: string; name: string }
  | { type: 'clear'; sourceAgency?: string };

export interface PolymerAnimationSnippet {
  name: string;
  curves: Record<string, Array<{ time: number; intensity: number }>>;
  maxTime: number;
  loop: boolean;
  snippetCategory: string;
  snippetPriority: number;
  snippetPlaybackRate: number;
  snippetIntensityScale: number;
  metadata?: Record<string, unknown>;
}

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
      agency: 'blink';
      requestId: string;
      snippet: PolymerAnimationSnippet;
      options: { autoPlay?: boolean; [key: string]: unknown };
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
  | { type: 'ready'; agency: 'character' | 'blink' | 'animation' }
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
  removeSnippet(name: string): void;
  dispose(): void;
}

export interface CharacterAgencySnapshot {
  blink: BlinkState;
  animation: AnimationState;
}

export type CharacterAgencyDispatch =
  | { agency: 'blink'; command: BlinkDispatch }
  | { agency: 'animation'; command: AnimationDispatch };

export interface CharacterAgencies {
  input: PolymerInputStream<CharacterAgencyDispatch>;
  events: PolymerStream<PolymerDomainEvent>;
  effects: PolymerStream<PolymerEffectEvent>;
  dispatch(message: CharacterAgencyDispatch): void;
  snapshot(): CharacterAgencySnapshot;
  agency(name: 'animation'): AnimationAgency;
  agency(name: 'blink'): BlinkAgency;
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
export function createCharacterAgencies(config?: { blink?: BlinkAgencyConfig; animation?: AnimationAgencyConfig }): CharacterAgencies;
