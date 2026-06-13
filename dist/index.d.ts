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

export type PolymerEffectEvent =
  | {
      type: 'animation.scheduleSnippet';
      agency: 'blink';
      effectId: string;
      snippet: PolymerAnimationSnippet;
      options: { autoPlay: true };
    }
  | {
      type: 'animation.removeSnippet';
      agency: 'blink';
      effectId: string;
      name: string;
    };

export type PolymerCommandEvent = PolymerEffectEvent;

export type PolymerStateEvent =
  | { type: 'state'; agency: 'blink'; state: BlinkState };

export type PolymerDomainEvent =
  | {
      type: 'blinkPlanned';
      agency: 'blink';
      plan: Record<string, unknown>;
      snippetName: string;
      nextDelayMs: number | null;
    }
  | { type: 'signal'; agency: 'blink'; signal: 'blink-fast'; plan: Record<string, unknown> }
  | { type: 'ready'; agency: 'character' | 'blink' }
  | { type: 'error'; agency: string; message: string };

export type PolymerStatusEvent = PolymerStateEvent | PolymerDomainEvent;

export interface BlinkAgency {
  input: PolymerInputStream<BlinkDispatch>;
  state: PolymerStream<PolymerStateEvent>;
  events: PolymerStream<PolymerDomainEvent>;
  effects: PolymerStream<PolymerEffectEvent>;
  dispatch(command: BlinkDispatch): void;
  snapshot(): BlinkState;
  subscribeInput(listener: (event: { type: 'command'; agency: 'blink'; command: BlinkDispatch }) => void): () => void;
  subscribeState(listener: (event: PolymerStateEvent) => void): () => void;
  subscribeEvents(listener: (event: PolymerDomainEvent) => void): () => void;
  subscribeEffects(listener: (event: PolymerEffectEvent) => void): () => void;
  /** Compatibility alias: state + events. Prefer subscribeState/subscribeEvents. */
  subscribe(listener: (event: PolymerStatusEvent) => void): () => void;
  /** Compatibility alias: state + events. Prefer subscribeState/subscribeEvents. */
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

export interface CharacterAgencySnapshot {
  blink: BlinkState;
}

export type CharacterAgencyDispatch =
  | { agency: 'blink'; command: BlinkDispatch };

export interface CharacterAgencies {
  input: PolymerInputStream<CharacterAgencyDispatch>;
  state: PolymerStream<PolymerStateEvent>;
  events: PolymerStream<PolymerDomainEvent>;
  effects: PolymerStream<PolymerEffectEvent>;
  dispatch(message: CharacterAgencyDispatch): void;
  snapshot(): CharacterAgencySnapshot;
  agency(name: 'blink'): BlinkAgency;
  agency(name: string): unknown | null;
  subscribeInput(listener: (event: { type: 'command'; agency: string; message: CharacterAgencyDispatch }) => void): () => void;
  subscribeState(listener: (event: PolymerStateEvent) => void): () => void;
  subscribeEvents(listener: (event: PolymerDomainEvent) => void): () => void;
  subscribeEffects(listener: (event: PolymerEffectEvent) => void): () => void;
  /** Compatibility alias: state + events. Prefer subscribeState/subscribeEvents. */
  subscribe(listener: (event: PolymerStatusEvent) => void): () => void;
  /** Compatibility alias: state + events. Prefer subscribeState/subscribeEvents. */
  subscribeStatus(listener: (event: PolymerStatusEvent) => void): () => void;
  /** Compatibility alias for effects. Prefer subscribeEffects. */
  subscribeCommands(listener: (event: PolymerEffectEvent) => void): () => void;
  dispose(): void;
}

export function createBlinkAgency(config?: BlinkAgencyConfig): BlinkAgency;
export function createCharacterAgencies(config?: { blink?: BlinkAgencyConfig }): CharacterAgencies;
