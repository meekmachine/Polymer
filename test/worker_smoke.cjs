const { Worker } = require('node:worker_threads');
const path = require('node:path');

const workerPath = path.resolve(__dirname, '../dist/worker/blink-worker.js');

const worker = new Worker(`
  const { parentPort } = require('node:worker_threads');
  global.self = {
    postMessage(message) {
      parentPort.postMessage(message);
    },
    onmessage: null,
  };
  parentPort.on('message', (message) => {
    if (typeof global.self.onmessage === 'function') {
      global.self.onmessage({ data: message });
    }
  });
  require(${JSON.stringify(workerPath)});
`, { eval: true });

const timeout = setTimeout(() => {
  worker.terminate();
  console.error('Worker smoke test timed out.');
  process.exit(1);
}, 1000);

worker.on('message', (message) => {
  if (message.stream === 'status' && message.event?.type === 'ready') {
    worker.postMessage({
      type: 'dispatch',
      id: 'blink',
      command: { type: 'triggerBlink', options: { burstCount: 3 } },
    });
    return;
  }

  if (message.stream === 'commands' && message.event?.type === 'scheduleSnippet') {
    const blinkCount = message.event.snippet?.metadata?.blinkCount;
    clearTimeout(timeout);
    worker.terminate();
    if (blinkCount !== 3) {
      console.error(`Expected worker burst blinkCount=3, got ${blinkCount}`);
      process.exit(1);
    }
    console.log('worker smoke ok');
  }
});

worker.on('error', (error) => {
  clearTimeout(timeout);
  console.error(error);
  process.exit(1);
});

worker.postMessage({
  type: 'createBlinkAgency',
  id: 'blink',
  config: { frequency: 10 },
});
