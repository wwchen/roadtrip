import assert from 'node:assert/strict';
import { mkdtemp, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { test } from 'node:test';
import { fileURLToPath } from 'node:url';
import { importerDependenciesChanged } from './importer-dependencies-changed.mjs';

const run = promisify(execFile);
const SCRIPT = fileURLToPath(new URL('./importer-dependencies-changed.mjs', import.meta.url));

function lockfile(overrides = {}) {
  const packages = {
    '': { name: 'roadtrip', workspaces: ['frontend', 'packages/*'] },
    frontend: { name: 'roadtrip-frontend', dependencies: { '@lew-ds/lds-react': '1.0.1' } },
    'node_modules/roadtrip-frontend': { resolved: 'frontend', link: true },
    'packages/worker': {
      name: '@roadtrip/worker',
      version: '0.1.0',
      dependencies: { '@roadtrip/db': '0.1.0', yaml: '2.8.0' },
      devDependencies: { vitest: '4.0.0' },
    },
    'node_modules/@roadtrip/worker': { resolved: 'packages/worker', link: true },
    'packages/db': { name: '@roadtrip/db', version: '0.1.0', dependencies: { 'drizzle-orm': '0.45.4' } },
    'node_modules/@roadtrip/db': { resolved: 'packages/db', link: true },
    'node_modules/@lew-ds/lds-react': { version: '1.0.1', integrity: 'sha512-lds1' },
    'node_modules/yaml': { version: '2.8.0', integrity: 'sha512-yaml280' },
    'node_modules/drizzle-orm': {
      version: '0.45.4',
      integrity: 'sha512-drizzle',
      peerDependencies: { postgres: '*', kysely: '*' },
      peerDependenciesMeta: { kysely: { optional: true } },
    },
    'node_modules/postgres': { version: '3.4.9', integrity: 'sha512-postgres' },
    'node_modules/vitest': { version: '4.0.0', integrity: 'sha512-vitest4' },
  };
  return { lockfileVersion: 3, packages: { ...packages, ...overrides } };
}

test('identical lockfiles change nothing the importers load', () => {
  assert.equal(importerDependenciesChanged(lockfile(), lockfile()), false);
});

test('a bump confined to the frontend does not count', () => {
  const head = lockfile({ 'node_modules/@lew-ds/lds-react': { version: '2.0.0', integrity: 'sha512-lds2' } });
  assert.equal(importerDependenciesChanged(lockfile(), head), false);
});

test('a bump of a dependency the worker loads counts', () => {
  const head = lockfile({ 'node_modules/yaml': { version: '2.8.1', integrity: 'sha512-yaml281' } });
  assert.equal(importerDependenciesChanged(lockfile(), head), true);
});

test('a bump reached through @roadtrip/db and a peer dependency counts', () => {
  const head = lockfile({ 'node_modules/postgres': { version: '3.4.10', integrity: 'sha512-postgres2' } });
  assert.equal(importerDependenciesChanged(lockfile(), head), true);
});

test('a bump of a dev dependency of the worker does not count', () => {
  const head = lockfile({ 'node_modules/vitest': { version: '4.1.0', integrity: 'sha512-vitest41' } });
  assert.equal(importerDependenciesChanged(lockfile(), head), false);
});

test('a copy nested under the worker shadows the hoisted one', () => {
  const nested = { 'packages/worker/node_modules/yaml': { version: '2.8.0', integrity: 'sha512-yaml280' } };
  const base = lockfile(nested);
  const hoistedBump = lockfile({ ...nested, 'node_modules/yaml': { version: '3.0.0', integrity: 'sha512-yaml3' } });
  const nestedBump = lockfile({ 'packages/worker/node_modules/yaml': { version: '2.8.1', integrity: 'sha512-yaml281' } });

  assert.equal(importerDependenciesChanged(base, hoistedBump), false);
  assert.equal(importerDependenciesChanged(base, nestedBump), true);
});

test('a new dependency of the worker counts', () => {
  const head = lockfile({
    'packages/worker': {
      name: '@roadtrip/worker',
      version: '0.1.0',
      dependencies: { '@roadtrip/db': '0.1.0', yaml: '2.8.0', zod: '4.0.0' },
      devDependencies: { vitest: '4.0.0' },
    },
    'node_modules/zod': { version: '4.0.0', integrity: 'sha512-zod' },
  });
  assert.equal(importerDependenciesChanged(lockfile(), head), true);
});

test('an optional peer that something else installs does not count', () => {
  const head = lockfile({ 'node_modules/kysely': { version: '0.28.0', integrity: 'sha512-kysely' } });
  assert.equal(importerDependenciesChanged(lockfile(), head), false);
});

test('a workspace the importers load appearing or disappearing counts', () => {
  const without = lockfile();
  delete without.packages['packages/worker'];
  delete without.packages['node_modules/@roadtrip/worker'];

  assert.equal(importerDependenciesChanged(without, lockfile()), true);
  assert.equal(importerDependenciesChanged(lockfile(), without), true);
  assert.equal(importerDependenciesChanged(without, without), false);
});

test('the command prints true or false for two lockfile paths', async () => {
  const dir = await mkdtemp(path.join(tmpdir(), 'importer-deps-'));
  const base = path.join(dir, 'base.json');
  const head = path.join(dir, 'head.json');
  await writeFile(base, JSON.stringify(lockfile()));
  await writeFile(head, JSON.stringify(lockfile({ 'node_modules/yaml': { version: '2.8.1', integrity: 'x' } })));

  const changed = await run(process.execPath, [SCRIPT, base, head]);
  const unchanged = await run(process.execPath, [SCRIPT, base, base]);

  assert.equal(changed.stdout.trim(), 'true');
  assert.equal(unchanged.stdout.trim(), 'false');
});
