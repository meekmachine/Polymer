export interface BlinkState {
  enabled: boolean;
  frequency: number;
  duration: number;
  intensity: number;
  randomness: number;
  leftEyeIntensity: number | null;
  rightEyeIntensity: number | null;
  burstEnabled: boolean;
  burstChance: number;
  burstCount: number;
  burstGap: number;
  lastBlinkTime: number | null;
  scheduledBlinkCount: number;
  scheduledBurstCount: number;
}

export interface BlinkAgencyConfig extends Partial<BlinkState> {}

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
  | { type: 'setBurstChance'; value: number }
  | { type: 'setBurstCount'; value: number }
  | { type: 'setBurstGap'; value: number }
  | { type: 'configure'; config: BlinkAgencyConfig }
  | { type: 'reset' };

export interface BlinkTriggerOptions {
  intensity?: number;
  duration?: number;
  burstCount?: number;
  burstGap?: number;
}

export interface PolymerSnippetCommand {
  type: 'scheduleSnippet' | 'removeSnippet';
  agency: 'blink';
  snippet?: unknown;
  name?: string;
  options?: { autoPlay?: boolean };
}

export interface PolymerStatusEvent {
  type: 'state' | 'blinkPlanned' | 'signal' | 'ready' | 'error';
  agency: 'blink';
  state?: BlinkState;
  signal?: 'blink-fast';
  message?: string;
  [key: string]: unknown;
}

export interface BlinkAgency {
  dispatch(command: BlinkDispatch): void;
  snapshot(): BlinkState;
  subscribe(listener: (event: PolymerStatusEvent) => void): () => void;
  subscribeStatus(listener: (event: PolymerStatusEvent) => void): () => void;
  subscribeCommands(listener: (event: PolymerSnippetCommand) => void): () => void;
  enable(): void;
  disable(): void;
  triggerBlink(options?: BlinkTriggerOptions): void;
  setFrequency(value: number): void;
  setDuration(value: number): void;
  setIntensity(value: number): void;
  setRandomness(value: number): void;
  setBurstEnabled(value: boolean): void;
  setBurstChance(value: number): void;
  setBurstCount(value: number): void;
  setBurstGap(value: number): void;
  reset(): void;
  dispose(): void;
}

export function createBlinkAgency(config?: BlinkAgencyConfig): BlinkAgency;
