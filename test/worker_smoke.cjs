const assert = require('node:assert');
const path = require('node:path');
const { Worker } = require('node:worker_threads');

const workerPath = path.join(__dirname, '..', 'dist', 'worker', 'agency-worker.js');

async function main() {
  const worker = new Worker(`
    const { parentPort } = require('node:worker_threads');
    globalThis.self = globalThis;
    globalThis.postMessage = (message) => parentPort.postMessage(message);
    globalThis.addEventListener = (type, listener) => {
      if (type !== 'message') return;
      parentPort.on('message', (data) => listener({ data }));
    };
    require(${JSON.stringify(workerPath)});
  `, { eval: true });
  const messages = [];

  worker.on('message', (message) => {
    messages.push(message);
  });
  worker.on('error', (error) => {
    messages.push({ stream: 'error', event: { message: error.message, stack: error.stack } });
  });

  await new Promise((resolve, reject) => {
    worker.once('online', resolve);
    worker.once('error', reject);
  });

  worker.postMessage({
    type: 'createCharacterAgencies',
    id: 'character-a',
    config: { blink: { frequency: 45 } },
  });

  await waitFor(messages, () => messages.some(
    (message) => message.stream === 'events' && message.event?.type === 'ready',
  ));

  worker.postMessage({
    type: 'dispatch',
    id: 'character-a',
    message: { agency: 'blink', command: { type: 'triggerBlink', options: { burstCount: 2 } } },
  });

  await waitFor(messages, () => messages.some(
    (message) => message.stream === 'events' && message.event?.type === 'animationSnippetScheduled',
  ));
  await waitFor(messages, () => messages.some(
    (message) => message.stream === 'events' && message.event?.signal === 'blink-fast',
  ));
  assert(!messages.some(
    (message) => message.stream === 'effects' && message.event?.type === 'animation.scheduleSnippet',
  ));

  worker.postMessage({
    type: 'dispatch',
    id: 'character-a',
    message: {
      agency: 'lipSync',
      command: {
        type: 'startTimeline',
        timeline: {
          name: 'worker:lipSync',
          source: 'worker',
          visemes: [{ visemeId: 1, offsetMs: 0, durationMs: 120 }],
        },
      },
    },
  });

  await waitFor(messages, () => messages.some(
    (message) => message.stream === 'events' && message.event?.type === 'lipSyncTimelineStarted',
  ));

  assert(messages.some(
    (message) => message.stream === 'events' && message.event?.type === 'animationSnippetScheduled',
  ));

  worker.postMessage({
    type: 'createGestureAgency',
    id: 'gesture-a',
    config: {
      gestures: {
        wave: {
          id: 'wave',
          name: 'Wave',
          durationMs: 120,
          bones: {
            HAND_L: { rotation: [0, 0, 0.3826834323650898, 0.9238795325112867] },
          },
        },
      },
      emojiMappings: { '👋': 'wave' },
    },
  });

  await waitFor(messages, () => messages.some(
    (message) => message.id === 'gesture-a'
      && message.stream === 'events'
      && message.event?.type === 'ready',
  ));

  worker.postMessage({
    type: 'dispatch',
    id: 'gesture-a',
    message: { agency: 'gesture', command: { type: 'playEmoji', emoji: '👋' } },
  });

  await waitFor(messages, () => messages.some(
    (message) => message.id === 'gesture-a'
      && message.stream === 'events'
      && message.event?.type === 'gestureScheduled',
  ));

  worker.postMessage({ type: 'dispose', id: 'character-a' });
  worker.postMessage({ type: 'dispose', id: 'gesture-a' });
  await worker.terminate();
}

function waitFor(messages, predicate, timeoutMs = 1500) {
  const started = Date.now();
  return new Promise((resolve, reject) => {
    const tick = () => {
      const workerError = messages.find((message) => message.stream === 'error');
      if (workerError) {
        reject(new Error(`Worker error: ${workerError.event.message}\n${workerError.event.stack}`));
        return;
      }
      if (predicate()) {
        resolve();
        return;
      }
      if (Date.now() - started > timeoutMs) {
        reject(new Error(`Timed out waiting for worker message. Received: ${JSON.stringify(messages)}`));
        return;
      }
      setTimeout(tick, 20);
    };
    tick();
  });
}

main().catch((error) => {
  console.error(error);
  process.exit(1);
});
