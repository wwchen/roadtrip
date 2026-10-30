#!/usr/bin/env node
import { execFileSync, spawnSync } from 'node:child_process';
import { existsSync, mkdtempSync, readFileSync, readdirSync, rmSync, statSync, writeFileSync } from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { parseArgs } from 'node:util';
import { resultOf } from './etl-oracle/case-shape.mjs';
import { compareCallCase, compareEtlCase, floorProblems, summarise } from './etl-oracle/compare.mjs';
import { registryWithOverride, requireEtlEntry, seamProblems } from './etl-oracle/registry-seam.mjs';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const REPO_ROOT = path.resolve(HERE, '..');
const ORACLE_DIR = path.join(HERE, 'etl-oracle');
const WORKER_DIR = path.join(REPO_ROOT, 'packages/worker');
const KOTLIN_MAIN = path.join(REPO_ROOT, 'backend/src/main/kotlin');
const KOTLIN_CLASSES = path.join(REPO_ROOT, 'backend/build/classes/kotlin/main');
const REGISTRY_PATH = path.join(REPO_ROOT, 'backend/src/main/resources/poi-registry.yaml');
const MANIFEST = JSON.parse(readFileSync(path.join(ORACLE_DIR, 'adapters.json'), 'utf8'));
const WORKER_PACKAGE = '@roadtrip/worker';
const CLASSPATH_ENV = 'ETL_ORACLE_CLASSPATH';
const DEFAULT_FETCHED_AT = '2026-06-17T00:00:00Z';
const MAX_FAILURES_SHOWN = 20;
const MAX_INPUT_SHOWN = 700;
const DEFAULT_EXPLORE_CASES = 500;
const USAGE = [
  'usage: node scripts/etl-oracle.mjs [check]',
  '       node scripts/etl-oracle.mjs explore --generator <name> --seed <n> [--cases <n>]',
  '',
  'check (the default and the only gate): runs the worker unit tests under a recorder that captures every',
  'parse and transform they perform on the adapters in scripts/etl-oracle/adapters.json, replays the same',
  'inputs through the real Kotlin adapters on the JVM, and compares the two outputs. It also replays the',
  'committed call cases in scripts/etl-oracle/cases/*.jsonl against the Kotlin function they name. Fully',
  'deterministic: the same tests and case files give the same answer every run.',
  '',
  'explore: draws cases from scripts/etl-oracle/explore/<name>.mjs with a seeded generator and compares them',
  'the same way, against the built worker (dist). --seed is required, so a run is always reproducible.',
  'It is exploratory and must never be part of a default or CI run.',
  '',
  'Both sides build the adapter the same way the importer does: they load backend/src/main/resources/poi-registry.yaml,',
  "look the recorded etl slug up in it, and hand that EtlEntry to their own adapter registry (Kotlin's poiAdapters /",
  "campsiteAdapters, TypeScript's adapterRegistry). Geometry, args, host, tenant and booking providers are therefore the",
  "shipped ones on both sides. Only the row's subcategory and agency are overridden, from what the unit test's own ctx",
  'reported.',
  '',
  'Because the recorded TypeScript results come from the unit test\'s own etl instance while the replay constructs from',
  'the shipped row, check refuses a case whose instance differs from the one that row builds, whose slug the registry',
  'does not declare, whose adapters.json export the row does not name, or that read an arg the registry does not',
  'declare with that value.',
  '',
  'check also fails, whatever the comparison says, when an adapter in adapters.json has fewer recorded cases',
  'than its minimumCases, fewer compared through transform than its minimumTransformed, or when a call in',
  'adapters.json has fewer committed cases than its minimumCases: an adapter nothing exercises would otherwise',
  'print a green run. Per-adapter counts are printed.',
  '',
  'Blind spots, all of them by construction:',
  '  - An absent field, a null, an empty list, an empty object, and a null element inside a list (so [null, 1]',
  '    equals [1]) are indistinguishable: all are dropped from both sides before they are compared.',
  '  - Opaque JSON fields (sourcePayload, and raw price or schedule passthrough such as Campflare stores) are',
  '    parsed and re-serialised, so key order and the vendor spelling of a number (1.10, 25.0, 1e2) are lost.',
  '    The oracle says nothing about them; the unit tests and the importer differential are the only instruments.',
  '  - Strings, numbers and booleans are compared as JSON values, so 4 and 4.0 are equal.',
  '  - The recorded envelope keeps url, part, fetched_at and payload; fetched_at is carried to both sides and',
  '    compared, but the other envelope fields (fetcher, response, headers) are fixed placeholders.',
  '  - An adapter that reads the clock (Instant.now) cannot be reconciled by any replay.',
  '',
  `The JVM classpath is read from Gradle (:backend:printRuntimeClasspath); set ${CLASSPATH_ENV} to reuse one.`,
  'The backend must have been compiled (./gradlew :backend:classes), because the oracle runs build/classes.',
].join('\n');

function fail(message) {
  console.error(message);
  process.exit(2);
}

function newestMtime(dir) {
  let newest = 0;
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name);
    newest = Math.max(newest, entry.isDirectory() ? newestMtime(full) : statSync(full).mtimeMs);
  }
  return newest;
}

function assertKotlinBuilt() {
  if (!existsSync(KOTLIN_CLASSES)) fail(`${KOTLIN_CLASSES} is missing: run ./gradlew :backend:classes first`);
  if (newestMtime(KOTLIN_MAIN) > newestMtime(KOTLIN_CLASSES)) {
    fail('backend/src/main is newer than backend/build/classes: run ./gradlew :backend:classes so the oracle is not the previous Kotlin');
  }
}

function classpath() {
  const given = process.env[CLASSPATH_ENV];
  if (given !== undefined && given !== '') return given;
  const output = execFileSync('./gradlew', ['-q', '-I', path.join(ORACLE_DIR, 'printcp.gradle'), ':backend:printRuntimeClasspath'], {
    cwd: REPO_ROOT,
    encoding: 'utf8',
    maxBuffer: 1 << 26,
  });
  const line = output.split('\n').find((candidate) => candidate.startsWith('CP='));
  if (line === undefined) fail('Gradle did not print a CP= line for :backend:printRuntimeClasspath');
  return line.slice('CP='.length);
}

function replayOnJvm(cases, scratch) {
  const cp = classpath();
  const classes = path.join(scratch, 'classes');
  execFileSync('javac', ['-cp', cp, '-d', classes, path.join(ORACLE_DIR, 'Oracle.java')], { stdio: ['ignore', 'ignore', 'inherit'] });
  const file = path.join(scratch, 'cases.jsonl');
  writeFileSync(file, `${cases.map((current) => JSON.stringify(current)).join('\n')}\n`);
  const output = execFileSync('java', ['-cp', `${cp}${path.delimiter}${classes}`, 'Oracle', file, REGISTRY_PATH], {
    encoding: 'utf8',
    maxBuffer: 1 << 28,
  });
  return output.replace(/\n$/, '').split('\n').filter((line) => line !== '').map((line) => JSON.parse(line));
}

function recordUnitTestCases(scratch) {
  const out = path.join(scratch, 'recorded.jsonl');
  const run = spawnSync('npx', ['vitest', 'run', '--config', path.join(ORACLE_DIR, 'vitest.config.mjs')], {
    cwd: WORKER_DIR,
    env: { ...process.env, ETL_ORACLE_OUT: out },
    stdio: ['ignore', 'ignore', 'inherit'],
  });
  if (!existsSync(out)) fail('the worker unit tests recorded no adapter cases');
  const cases = readFileSync(out, 'utf8').trim().split('\n').map((line) => JSON.parse(line));
  return { cases, unitTestsPassed: run.status === 0 };
}

function buildWorker() {
  execFileSync('node', [path.join(HERE, 'build-workspaces.mjs'), WORKER_PACKAGE], { cwd: REPO_ROOT, stdio: ['ignore', 'ignore', 'inherit'] });
}

async function workerExport(moduleName, exportName) {
  const url = pathToFileURL(path.join(WORKER_DIR, 'dist', `${moduleName}.js`)).href;
  return (await import(url))[exportName];
}

function withFetchedAt(descriptor) {
  const inputs = Object.fromEntries(
    Object.entries(descriptor.inputs).map(([slug, envelopes]) => [
      slug,
      envelopes.map((envelope) => ({ ...envelope, fetchedAt: envelope.fetchedAt ?? DEFAULT_FETCHED_AT })),
    ]),
  );
  return { ...descriptor, inputs };
}

function fetchedAtOf(inputs) {
  return Object.values(inputs).flatMap((envelopes) => envelopes.map((envelope) => envelope.fetchedAt));
}

function callCases() {
  const dir = path.join(ORACLE_DIR, 'cases');
  const cases = [];
  for (const name of readdirSync(dir).filter((file) => file.endsWith('.jsonl')).sort()) {
    for (const line of readFileSync(path.join(dir, name), 'utf8').split('\n').filter((text) => text !== '')) {
      const spec = JSON.parse(line);
      const entry = MANIFEST.calls[spec.call];
      if (entry === undefined) fail(`${name}: no call named ${spec.call} in adapters.json`);
      cases.push({ kind: 'call', call: spec.call, file: name, ...entry, args: spec.args });
    }
  }
  return cases;
}

async function runCallsOnTs(cases) {
  const { parseEnvelope } = await import('@roadtrip/worker');
  const scratch = mkdtempSync(path.join(os.tmpdir(), 'etl-oracle-call-'));
  try {
    const results = [];
    for (const [index, current] of cases.entries()) {
      const fn = await workerExport(current.module, current.export);
      const args = current.args.map((arg, position) => {
        if (current.argTypes[position] !== 'json') return arg;
        const file = path.join(scratch, `arg-${index}-${position}.json`);
        writeFileSync(file, `{"fetcher":"f","fetcher_version":"1","fetched_at":"t","request":{"url":"u","method":"GET"},"response":{"status":200},"payload":${arg}}`);
        return parseEnvelope(file).payload;
      });
      try {
        results.push({ threw: false, value: fn(...args) });
      } catch {
        results.push({ threw: true, value: null });
      }
    }
    return results;
  } finally {
    rmSync(scratch, { recursive: true, force: true });
  }
}

function describeFailure(failure) {
  const shown = JSON.stringify(failure.case.inputs ?? failure.case.args ?? {}).slice(0, MAX_INPUT_SHOWN);
  const label = failure.case.test ?? failure.case.call ?? failure.case.adapter;
  return `CASE ${failure.index} [${label}]\n  inputs ${shown}\n  ${failure.problems.join('\n  ')}`;
}

function report(title, summary) {
  console.log(`${title}: ${summary.compared} cases, ${summary.failures.length} disagreement(s)`);
  for (const failure of summary.failures.slice(0, MAX_FAILURES_SHOWN)) console.log(describeFailure(failure));
  return summary.failures.length === 0;
}

async function check(scratch) {
  assertKotlinBuilt();
  buildWorker();
  const { cases: recordedRaw, unitTestsPassed } = recordUnitTestCases(scratch);
  const recorded = recordedRaw.map((current) => ({ ...current, fetchedAt: fetchedAtOf(current.inputs) }));
  if (!unitTestsPassed) console.log('the worker unit tests failed; the recorded cases are compared anyway');
  const { adapterRegistry, loadPoiRegistry } = await import('@roadtrip/worker');
  const seam = seamProblems(recorded, loadPoiRegistry(REGISTRY_PATH), MANIFEST.adapters, adapterRegistry);
  for (const problem of seam) console.log(`SEAM: ${problem}`);
  if (seam.length > 0) return false;
  const etlKotlin = replayOnJvm(recorded, scratch);
  const transformed = recorded.filter((current) => current.tsParse === 'ok' && current.tsTransformRan).length;
  const etlOk = report(`recorded unit-test cases (${transformed} compared through transform)`, summarise(recorded, etlKotlin, compareEtlCase));
  const calls = callCases();
  const callCaseCounts = {};
  for (const current of calls) callCaseCounts[current.call] = (callCaseCounts[current.call] ?? 0) + 1;
  const floors = floorProblems(recorded, MANIFEST.adapters, MANIFEST.calls, callCaseCounts);
  for (const [name, count] of Object.entries(floors.counts)) {
    const entry = MANIFEST.adapters[name];
    console.log(`  ${name}: ${count.cases} recorded (floor ${entry.minimumCases}), ${count.transformed} through transform (floor ${entry.minimumTransformed})`);
  }
  for (const [name, count] of Object.entries(callCaseCounts)) console.log(`  call ${name}: ${count} committed (floor ${MANIFEST.calls[name].minimumCases})`);
  for (const problem of floors.problems) console.log(`FLOOR: ${problem}`);
  let callOk = true;
  if (calls.length > 0) {
    const tsResults = await runCallsOnTs(calls);
    const kotlinResults = replayOnJvm(calls, scratch);
    callOk = report('committed call cases', summarise(tsResults.map((value, index) => ({ ...calls[index], ...value })), kotlinResults, compareCallCase));
  }
  return etlOk && callOk && unitTestsPassed && floors.problems.length === 0;
}

async function runEtlOnTs(descriptor) {
  const { InputBundle, TransformCtx, adapterRegistry, loadPoiRegistry, parseEnvelope } = await import('@roadtrip/worker');
  const entry = MANIFEST.adapters[descriptor.adapter];
  const registry = loadPoiRegistry(REGISTRY_PATH);
  const etlEntry = requireEtlEntry(registry, descriptor.slug);
  const factory = adapterRegistry[etlEntry.adapter];
  if (factory === undefined) fail(`no adapter named '${etlEntry.adapter}' in the TypeScript adapterRegistry`);
  const etl = factory(etlEntry).etl;
  const scratch = mkdtempSync(path.join(os.tmpdir(), 'etl-oracle-ts-'));
  try {
    let counter = 0;
    const captures = new Map(
      Object.entries(descriptor.inputs).map(([slug, envelopes]) => [
        slug,
        envelopes.map((envelope) => {
          counter += 1;
          const file = path.join(scratch, `envelope-${counter}.json`);
          const part = envelope.part === null ? '' : `,"part":${JSON.stringify(envelope.part)}`;
          writeFileSync(
            file,
            `{"fetcher":"f","fetcher_version":"1","fetched_at":${JSON.stringify(envelope.fetchedAt)},"request":{"url":${JSON.stringify(envelope.url)},"method":"GET"},"response":{"status":200}${part},"payload":${envelope.payload}}`,
          );
          return parseEnvelope(file);
        }),
      ]),
    );
    const ctx = TransformCtx.load('/unused', registryWithOverride(registry, descriptor.slug, descriptor.registry));
    const fetchedAt = [...captures.values()].flatMap((envelopes) => envelopes.map((envelope) => envelope.fetchedAt));
    const outcome = { ...descriptor, fetchedAt, tsParse: 'ok', tsParseBad: [], tsTransformRan: true, tsTransform: 'ok', tsResults: [] };
    try {
      for (const result of etl.parse(new InputBundle(captures))) {
        if (!result.ok) {
          outcome.tsParseBad.push(result.errors);
          continue;
        }
        try {
          for (const record of etl.transform(result.dto, ctx)) outcome.tsResults.push(resultOf(record, entry.opaqueFields));
        } catch {
          outcome.tsTransform = 'throws';
        }
      }
    } catch {
      outcome.tsParse = 'throws';
      outcome.tsTransform = 'skipped';
    }
    return outcome;
  } finally {
    rmSync(scratch, { recursive: true, force: true });
  }
}

async function explore(values, scratch) {
  if (values.generator === undefined) fail('explore needs --generator <name>');
  if (values.seed === undefined) fail('explore needs an explicit --seed, so that every run can be reproduced');
  const seed = Number(values.seed);
  if (!Number.isInteger(seed)) fail(`--seed must be an integer, got ${values.seed}`);
  const count = Number(values.cases ?? DEFAULT_EXPLORE_CASES);
  assertKotlinBuilt();
  buildWorker();
  const generator = await import(pathToFileURL(path.join(ORACLE_DIR, 'explore', `${values.generator}.mjs`)).href);
  const descriptors = generator.generate({ seed, count }).map((descriptor) =>
    withFetchedAt({ kind: 'etl', test: `${values.generator} seed ${seed}`, ...descriptor }),
  );
  const withTs = [];
  for (const descriptor of descriptors) withTs.push(await runEtlOnTs(descriptor));
  const kotlin = replayOnJvm(withTs, scratch);
  return report(`explore ${values.generator} (seed ${seed})`, summarise(withTs, kotlin, compareEtlCase));
}

async function main() {
  const { values, positionals } = parseArgs({
    allowPositionals: true,
    options: { generator: { type: 'string' }, seed: { type: 'string' }, cases: { type: 'string' }, help: { type: 'boolean' } },
  });
  if (values.help) {
    console.log(USAGE);
    return;
  }
  const mode = positionals[0] ?? 'check';
  if (mode !== 'check' && mode !== 'explore') fail(`${USAGE}\n\nunknown mode ${mode}`);
  const scratch = mkdtempSync(path.join(os.tmpdir(), 'etl-oracle-'));
  try {
    const ok = mode === 'check' ? await check(scratch) : await explore(values, scratch);
    process.exitCode = ok ? 0 : 1;
  } finally {
    rmSync(scratch, { recursive: true, force: true });
  }
}

await main();
