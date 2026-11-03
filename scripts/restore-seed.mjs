#!/usr/bin/env node
// Restores a seed built by build-seed.mjs: verifies its checksum and that it
// was built from this checkout's importer, migrations and captures, drops
// every schema the restoring role owns, loads the custom-format dump, and reapplies the read-only grants
// the app's role needs. With NEON_API_KEY and NEON_PROJECT_ID it first resets
// the Neon branch `seed` from `main`.
//
// Usage:
//   node scripts/restore-seed.mjs --manifest build/seed/manifest.json
//   node scripts/restore-seed.mjs --manifest <m> --database-url <url> [--docker-network <net>]

import { spawn } from 'node:child_process';
import { createReadStream } from 'node:fs';
import { readFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { parseArgs } from 'node:util';
import { sha256Of } from './build-seed.mjs';
import { cacheRefusal, provenanceFor } from './importer-differential.mjs';
import { createNeonClient } from './neon.mjs';
import { dockerTimeoutMs } from './schema-reference.mjs';
import { SEED_BRANCH, SEED_DATABASE, SEED_OWNER_ROLE, SEED_PARENT_BRANCH } from './seed-branches.mjs';

const REPO_ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');

export const SEED_GRANTS_RELATIVE_PATH = 'seed/grants.sql';

const CLIENT_IMAGE = 'postgis/postgis:16-3.4';
const CLIENT_URL_ENV = 'SEED_DATABASE_URL';
const EMPTY_DATABASE_SQL = `DO $$
DECLARE
  owned text;
BEGIN
  FOR owned IN
    SELECT nspname FROM pg_namespace
    WHERE (nspname = 'public' OR nspowner = (SELECT oid FROM pg_roles WHERE rolname = current_user))
      AND nspname NOT LIKE 'pg\\_%' AND nspname <> 'information_schema'
  LOOP
    EXECUTE format('DROP SCHEMA %I CASCADE', owned);
  END LOOP;
END $$;
CREATE SCHEMA public;
`;
const SEED_PASSES = 1;

function clientArgs(network, env, command) {
  return [
    'run',
    '--rm',
    '-i',
    ...(network === undefined ? [] : ['--network', network]),
    '-e',
    env,
    CLIENT_IMAGE,
    'sh',
    '-c',
    command,
  ];
}

function runClient(network, url, command, input) {
  return new Promise((resolve, reject) => {
    const child = spawn('docker', clientArgs(network, CLIENT_URL_ENV, command), {
      env: { ...process.env, [CLIENT_URL_ENV]: url },
      stdio: ['pipe', 'inherit', 'pipe'],
    });
    const timer = setTimeout(() => child.kill('SIGKILL'), dockerTimeoutMs());
    let stderr = '';
    child.stderr.on('data', (chunk) => {
      stderr += chunk;
    });
    child.once('error', reject);
    child.stdin.on('error', () => {});
    child.once('exit', (code, signal) => {
      clearTimeout(timer);
      if (code === 0) resolve();
      else reject(new Error(`${command.split(' ')[0]} exited ${code ?? signal}: ${stderr.trim()}`));
    });
    if (input.file !== undefined) createReadStream(input.file).pipe(child.stdin);
    else child.stdin.end(input.text);
  });
}

export function dockerRunners(network) {
  return {
    runSql: (url, sql) => runClient(network, url, `psql -v ON_ERROR_STOP=1 -q -d "$${CLIENT_URL_ENV}"`, { text: sql }),
    runRestore: (url, dumpPath) =>
      runClient(network, url, `pg_restore --no-owner --no-privileges --exit-on-error -d "$${CLIENT_URL_ENV}"`, {
        file: dumpPath,
      }),
  };
}

export async function restoreSeed({ manifest, dumpPath, databaseUrl, runSql, runRestore }) {
  const actual = await sha256Of(dumpPath);
  if (actual !== manifest.sha256) {
    throw new Error(`${path.basename(dumpPath)} has sha256 ${actual}, the manifest says ${manifest.sha256}`);
  }
  const wanted = await provenanceFor({
    targets: manifest.targets,
    sources: [],
    passes: SEED_PASSES,
    proveUpdateKeys: false,
  });
  const refusal = cacheRefusal(wanted, manifest.provenance);
  if (refusal !== undefined) throw new Error(`not restoring seed v${manifest.version}: ${refusal}`);

  const grants = await readFile(path.join(REPO_ROOT, SEED_GRANTS_RELATIVE_PATH), 'utf8');
  await runSql(databaseUrl, EMPTY_DATABASE_SQL);
  await runRestore(databaseUrl, dumpPath);
  await runSql(databaseUrl, grants);
}

export async function resetNeonSeedBranch(neon) {
  await neon.deleteBranch(SEED_BRANCH);
  await neon.createBranch(SEED_BRANCH, SEED_PARENT_BRANCH);
  return neon.connectionUri({ branchName: SEED_BRANCH, database: SEED_DATABASE, role: SEED_OWNER_ROLE, pooled: false });
}

async function main() {
  const { values } = parseArgs({
    options: {
      manifest: { type: 'string' },
      'database-url': { type: 'string' },
      'docker-network': { type: 'string' },
    },
    strict: true,
  });
  if (values.manifest === undefined) {
    console.error('--manifest is required');
    process.exitCode = 2;
    return;
  }
  const manifestPath = path.resolve(values.manifest);
  const manifest = JSON.parse(await readFile(manifestPath, 'utf8'));
  const dumpPath = path.join(path.dirname(manifestPath), manifest.dump);
  const databaseUrl =
    values['database-url'] ??
    (await resetNeonSeedBranch(
      createNeonClient({ apiKey: process.env.NEON_API_KEY, projectId: process.env.NEON_PROJECT_ID }),
    ));
  await restoreSeed({ manifest, dumpPath, databaseUrl, ...dockerRunners(values['docker-network']) });
  console.log(`restored seed v${manifest.version} (${manifest.dump})`);
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  await main();
}
