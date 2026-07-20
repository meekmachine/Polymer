import fs from 'node:fs';
import path from 'node:path';
import { pathToFileURL } from 'node:url';

const root = process.cwd();
const distPath = path.join(root, 'dist', 'index.js');
const errors = [];

function fail(message) {
  errors.push(message);
}

if (!fs.existsSync(distPath)) {
  fail('dist/index.js is missing. Run `pnpm build` before publishing or pinning this SHA.');
} else {
  const source = fs.readFileSync(distPath, 'utf8');

  if (!source.includes('import*as') && !source.includes('import * as')) {
    fail('dist/index.js should be native ESM and import Embody as an ES module.');
  }

  if (!source.includes('export const ')) {
    fail('dist/index.js should expose named ESM exports.');
  }

  for (const forbidden of ['require(', 'module.exports', 'Loom3', 'LoomLargeThree']) {
    if (source.includes(forbidden)) {
      fail(`dist/index.js should not contain legacy or CommonJS token: ${forbidden}`);
    }
  }

  if (errors.length === 0) {
    const polymer = await import(pathToFileURL(distPath).href);
    const requiredExports = [
      'createBlinkAgency',
      'createAnimationAgency',
      'createLipSyncAgency',
      'createTTSAgency',
      'detectAnnotationLaterality',
      'resolveBoneNames',
      'Embody',
      'initEmbodyCore',
      'EMBODY_CORE_ABI_VERSION',
      'createHairPhysicsSolver',
    ];

    for (const name of requiredExports) {
      if (typeof polymer[name] === 'undefined') {
        fail(`dist/index.js does not provide required export: ${name}`);
      }
    }
  }
}

if (errors.length > 0) {
  console.error(errors.map((error) => `- ${error}`).join('\n'));
  process.exit(1);
}

console.log('Built dist exposes the Polymer browser ESM API.');
