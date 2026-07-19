import fs from 'node:fs';
import path from 'node:path';
import process from 'node:process';

const root = process.cwd();
const packageJsonPath = path.join(root, 'package.json');
const packageJson = JSON.parse(fs.readFileSync(packageJsonPath, 'utf8'));
const scripts = packageJson.scripts ?? {};

const forbiddenConsumerScripts = [
  'preinstall',
  'install',
  'postinstall',
  'prepare',
  'prepack',
  'postpack',
];

const errors = [];

for (const scriptName of forbiddenConsumerScripts) {
  if (Object.prototype.hasOwnProperty.call(scripts, scriptName)) {
    errors.push(`Remove package script "${scriptName}"; Git-SHA consumers must not build Polymer during install.`);
  }
}

for (const field of ['main', 'module', 'types']) {
  const value = packageJson[field];
  if (typeof value !== 'string' || !value.startsWith('dist/')) {
    errors.push(`package.json "${field}" must point at a checked-in dist artifact.`);
    continue;
  }

  const artifactPath = path.join(root, value);
  if (!fs.existsSync(artifactPath)) {
    errors.push(`package.json "${field}" points at missing artifact: ${value}`);
  }
}

const files = Array.isArray(packageJson.files) ? packageJson.files : [];
if (!files.includes('dist')) {
  errors.push('package.json "files" must include "dist" so packed/Git installs expose prebuilt JS.');
}

const rootExport = packageJson.exports?.['.'];
if (!rootExport || rootExport.import !== './dist/index.js') {
  errors.push('package.json "exports[.]" must expose the CLJS-generated ESM artifact at dist/index.js.');
}

if (errors.length > 0) {
  console.error(errors.map((error) => `- ${error}`).join('\n'));
  process.exit(1);
}

console.log('Git install surface uses checked-in dist artifacts and has no consumer build lifecycle.');
