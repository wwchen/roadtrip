import assert from 'node:assert/strict';
import { mkdtemp, writeFile } from 'node:fs/promises';
import { existsSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import test from 'node:test';
import { loadPoiRegistry } from '@roadtrip/worker';
import {
  buildSeed,
  manifestProblems,
  readSeedTargets,
  SEED_MAX_LOGICAL_BYTES,
  seedTargetProblems,
  sha256Of,
} from './build-seed.mjs';
import { REGISTRY_RELATIVE_PATH } from './importer-differential.mjs';

const REPO_ROOT = path.resolve(import.meta.dirname, '..');
const registry = loadPoiRegistry(path.join(REPO_ROOT, REGISTRY_RELATIVE_PATH));

const BC_CAMPGROUNDS = 'BC Provincial Parks';
const ABC_SHA256 = 'ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad';

const manifest = (overrides = {}) => ({
  version: 1,
  dump: 'seed-v1.dump',
  sha256: ABC_SHA256,
  bytes: 3,
  logicalBytes: 1024,
  targets: [BC_CAMPGROUNDS],
  counts: { campgrounds: 1 },
  provenance: {},
  ...overrides,
});

test('the pinned seed targets are enabled registry targets that fill every table the app reads', () => {
  assert.deepEqual(seedTargetProblems(registry, readSeedTargets()), []);
});

test('a target list that leaves a table the app reads empty is refused, naming the table', () => {
  assert.deepEqual(seedTargetProblems(registry, [BC_CAMPGROUNDS]), [
    'no pinned target fills campsites, which the app reads',
  ]);
});

test('an unknown, duplicated or empty target list is refused', () => {
  assert.match(seedTargetProblems(registry, ['Nowhere Parks']).join('\n'), /unknown target\(s\): Nowhere Parks/);
  assert.ok(seedTargetProblems(registry, [...readSeedTargets(), BC_CAMPGROUNDS]).includes(`${BC_CAMPGROUNDS} is pinned twice`));
  assert.deepEqual(seedTargetProblems(registry, []), ['no seed targets are pinned']);
});

test('a manifest is refused over the logical size cap or without a whole positive version', () => {
  assert.deepEqual(manifestProblems(manifest()), []);
  assert.deepEqual(manifestProblems(manifest({ logicalBytes: SEED_MAX_LOGICAL_BYTES + 1 })), [
    `the seed is ${SEED_MAX_LOGICAL_BYTES + 1} bytes restored, over the ${SEED_MAX_LOGICAL_BYTES}-byte cap`,
  ]);
  assert.deepEqual(manifestProblems(manifest({ version: 0 })), ['version must be a positive integer, got 0']);
  assert.deepEqual(manifestProblems(manifest({ version: 1.5 })), ['version must be a positive integer, got 1.5']);
});

test('a manifest whose tables the app reads hold no rows is refused', () => {
  assert.deepEqual(manifestProblems(manifest({ counts: { campgrounds: 0 } })), ['the seed holds no campgrounds rows']);
});

test('the manifest checksum is the sha256 of the dump bytes', async () => {
  const dir = await mkdtemp(path.join(tmpdir(), 'seed-sha-'));
  const file = path.join(dir, 'dump');
  await writeFile(file, 'abc');
  assert.equal(await sha256Of(file), ABC_SHA256);
});

test('a differential that reports differences is refused before any container starts', async () => {
  const outDir = await mkdtemp(path.join(tmpdir(), 'seed-refused-'));
  let containers = 0;
  await assert.rejects(
    buildSeed({
      outDir,
      version: 1,
      differential: async () => ({ differences: [{ table: 'campgrounds' }], collisions: [] }),
      postgis: async () => {
        containers += 1;
      },
    }),
    /not building seed v1: 1 difference\(s\) between the two importers/,
  );
  assert.equal(containers, 0);
  assert.equal(existsSync(path.join(outDir, 'manifest.json')), false);
});

test('a clean differential that emitted no seed is refused rather than measured', async () => {
  const outDir = await mkdtemp(path.join(tmpdir(), 'seed-missing-'));
  await assert.rejects(
    buildSeed({
      outDir,
      version: 1,
      differential: async () => ({ differences: [], collisions: [] }),
      postgis: async () => assert.fail('no container should start'),
    }),
    /the differential emitted no seed/,
  );
});
