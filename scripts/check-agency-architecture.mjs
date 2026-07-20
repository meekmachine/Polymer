import fs from 'node:fs';
import path from 'node:path';

const repoRoot = path.resolve(new URL('..', import.meta.url).pathname);
const srcRoot = path.join(repoRoot, 'src', 'polymer');

const agencies = [
  { name: 'animation', dir: 'animation', ns: 'polymer.animation' },
  { name: 'blink', dir: 'blink', ns: 'polymer.blink' },
  { name: 'cameraContext', dir: 'camera_context', ns: 'polymer.camera-context' },
  { name: 'conversation', dir: 'conversation', ns: 'polymer.conversation' },
  { name: 'eyeHeadTracking', dir: 'eye_head', ns: 'polymer.eye-head' },
  { name: 'gaze', dir: 'gaze', ns: 'polymer.gaze' },
  { name: 'gesture', dir: 'gesture', ns: 'polymer.gesture' },
  { name: 'hair', dir: 'hair', ns: 'polymer.hair' },
  { name: 'lipSync', dir: 'lipsync', ns: 'polymer.lipsync' },
  { name: 'prosodic', dir: 'prosodic', ns: 'polymer.prosodic' },
  { name: 'transcription', dir: 'transcription', ns: 'polymer.transcription' },
  { name: 'tts', dir: 'tts', ns: 'polymer.tts' },
];

const errors = [];

function readFile(filePath) {
  return fs.readFileSync(filePath, 'utf8');
}

function exists(filePath) {
  return fs.existsSync(filePath);
}

function requireFile(agency, fileName) {
  const filePath = path.join(srcRoot, agency.dir, fileName);
  if (!exists(filePath)) {
    errors.push(`${agency.name}: missing ${fileName}`);
    return '';
  }
  return readFile(filePath);
}

function requireContains(agency, source, pattern, message) {
  const ok = pattern instanceof RegExp ? pattern.test(source) : source.includes(pattern);
  if (!ok) {
    errors.push(`${agency.name}: ${message}`);
  }
}

for (const agency of agencies) {
  const agencyDir = path.join(srcRoot, agency.dir);
  if (!exists(agencyDir)) {
    errors.push(`${agency.name}: missing agency directory ${agency.dir}`);
    continue;
  }

  const agencySource = requireFile(agency, 'agency.cljs');
  const stateSource = requireFile(agency, 'state.cljs');
  const schedulerSource = requireFile(agency, 'scheduler.cljs');

  const plannerPath = path.join(agencyDir, 'planner.cljs');
  const goapPath = path.join(agencyDir, 'goap.cljs');
  const hasPlanner = exists(plannerPath);
  const hasGoap = exists(goapPath);
  if (!hasPlanner && !hasGoap) {
    errors.push(`${agency.name}: missing planner.cljs or goap.cljs`);
  }

  requireContains(agency, agencySource, '[polymer.stream :as stream]', 'agency must use Polymer stream ports');
  requireContains(agency, agencySource, `${agency.ns}.scheduler`, 'agency must require its scheduler namespace');
  requireContains(agency, agencySource, `${agency.ns}.state`, 'agency must require its state namespace');
  requireContains(
    agency,
    agencySource,
    new RegExp(`${agency.ns}\\.(planner|goap)`),
    'agency must require planner.cljs or goap.cljs',
  );

  for (const token of [
    'stream/create-stream',
    ':input',
    ':events',
    ':effects',
    ':subscribeInput',
    ':subscribeEvents',
    ':subscribeEffects',
    ':dispatch',
    ':snapshot',
    ':dispose',
  ]) {
    requireContains(agency, agencySource, token, `agency API must expose ${token}`);
  }

  requireContains(agency, stateSource, 'visible-state', 'state namespace must expose visible-state');
  requireContains(agency, schedulerSource, ':dispose', 'scheduler must expose dispose');
  requireContains(
    agency,
    schedulerSource,
    /:queue|:schedulerQueue|queue\s*\(/,
    'scheduler must expose queue visibility for tests and diagnostics',
  );

  const servicePath = path.join(agencyDir, 'service.cljs');
  if (exists(servicePath)) {
    const serviceSource = readFile(servicePath);
    requireContains(
      agency,
      serviceSource,
      new RegExp(`${agency.ns}\\.agency`),
      'service.cljs may exist only as a compatibility adapter backed by the agency',
    );
    requireContains(
      agency,
      serviceSource,
      /compatibility|adapter|old LoomLarge|historical/i,
      'service.cljs must state that it is a compatibility adapter, not the agency implementation',
    );
  }
}

if (errors.length > 0) {
  console.error('Agency architecture check failed:');
  for (const error of errors) {
    console.error(`- ${error}`);
  }
  process.exit(1);
}

console.log('Agency architecture check passed for exported Polymer agencies.');
