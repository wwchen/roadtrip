import assert from 'node:assert/strict';
import { statSync } from 'node:fs';
import { mkdtemp, mkdir, rm, writeFile } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';
import { Worker } from 'node:worker_threads';
import { candidatePaths, findClientBoundaryViolations, findEnvReadViolations, findLayerViolations, LAYER_RULES, REPO_ROOT } from './check-server-only-imports.mjs';
import { findModuleSpecifiers } from './module-specifiers.mjs';

function isFile(candidate) {
  try {
    return statSync(candidate).isFile();
  } catch {
    return false;
  }
}

function isDirectory(candidate) {
  try {
    return statSync(candidate).isDirectory();
  } catch {
    return false;
  }
}


const WEB_SOURCE_ROOT = path.join(REPO_ROOT, 'apps/web/src');
const PATH_ENTRY_PREFIXES = ['server/', 'app/'];
const DIRECTORY_ENTRY_SUFFIX = '/';
const CLIENT = "'use client';\n";
const ADJACENT_BLOCK_COMMENTS = 64;
const SCAN_HARD_TIMEOUT_MS = 10_000;
const MODULE_SPECIFIERS_URL = new URL('./module-specifiers.mjs', import.meta.url).href;
const SCAN_WORKER = `
const { parentPort, workerData } = require('node:worker_threads');
import(workerData.moduleUrl).then(({ findModuleSpecifiers }) => {
  const found = findModuleSpecifiers(workerData.source);
  parentPort.postMessage({ found: found.length });
});
`;

function scanInWorker(source) {
  return new Promise((resolve, reject) => {
    const worker = new Worker(SCAN_WORKER, { eval: true, workerData: { moduleUrl: MODULE_SPECIFIERS_URL, source } });
    const timer = setTimeout(() => {
      worker.terminate();
      reject(new Error(`scan did not finish within ${SCAN_HARD_TIMEOUT_MS}ms`));
    }, SCAN_HARD_TIMEOUT_MS);
    worker.once('error', (error) => {
      clearTimeout(timer);
      reject(error);
    });
    worker.once('message', (result) => {
      clearTimeout(timer);
      worker.terminate();
      resolve(result);
    });
  });
}

async function fixture(files) {
  const root = await mkdtemp(path.join(os.tmpdir(), 'roadtrip-server-only-'));
  await Promise.all(
    Object.entries(files).map(async ([relative, source]) => {
      const target = path.join(root, 'apps/web', relative);
      await mkdir(path.dirname(target), { recursive: true });
      await writeFile(target, source);
    }),
  );
  return root;
}

test('rejects a client component importing @roadtrip/db in every import form', async (context) => {
  const root = await fixture({
    'src/components/Static.tsx': `${CLIENT}import { pois } from '@roadtrip/db';`,
    'src/components/Reexport.tsx': `${CLIENT}export { pois } from '@roadtrip/db';`,
    'src/components/Subpath.tsx': `${CLIENT}import { client } from '@roadtrip/db/client';`,
    'src/components/Dynamic.tsx': `${CLIENT}const db = await import('@roadtrip/db');`,
    'src/components/Required.cjs': `${CLIENT}const db = require('@roadtrip/db');`,
  });
  context.after(() => rm(root, { recursive: true, force: true }));

  const violations = await findClientBoundaryViolations(root);

  assert.equal(violations.length, 5);
  for (const name of ['Static.tsx', 'Reexport.tsx', 'Subpath.tsx', 'Dynamic.tsx', 'Required.cjs']) {
    assert.ok(
      violations.some((violation) => violation.startsWith(`apps/web/src/components/${name}:2: imports @roadtrip/db`)),
      name,
    );
  }
});

test('rejects a client component reaching the db through a helper chain', async (context) => {
  const root = await fixture({
    'src/components/Button.tsx': `${CLIENT}import { format } from './lib/format.ts';`,
    'src/components/lib/format.ts': "import { rows } from './rows.ts';",
    'src/components/lib/rows.ts': "import { pois } from '@roadtrip/db';",
  });
  context.after(() => rm(root, { recursive: true, force: true }));

  const violations = await findClientBoundaryViolations(root);

  assert.deepEqual(violations, [
    'apps/web/src/components/lib/rows.ts:1: imports @roadtrip/db (reached from client component apps/web/src/components/Button.tsx)',
  ]);
});

test('follows extensionless, compiled-extension, index and alias specifiers', async (context) => {
  const root = await fixture({
    'src/components/Extensionless.tsx': `${CLIENT}import { a } from './a';`,
    'src/components/a.ts': "import { b } from './b.js';",
    'src/components/b.ts': "import { c } from './c';",
    'src/components/c/index.ts': "import { d } from '@/lib/d';",
    'src/lib/d.ts': "import { pois } from '@roadtrip/db';",
  });
  context.after(() => rm(root, { recursive: true, force: true }));

  const violations = await findClientBoundaryViolations(root);

  assert.equal(violations.length, 1);
  assert.match(violations[0], /^apps\/web\/src\/lib\/d\.ts:1: imports @roadtrip\/db .*Extensionless\.tsx\)$/);
});

test('rejects a client component importing src/server/', async (context) => {
  const root = await fixture({
    'src/components/Search.tsx': `${CLIENT}import { search } from "../server/repo/campgroundSearchRepo.js";`,
    'src/components/Aliased.tsx': `${CLIENT}import { search } from '@/server/service/campgrounds';`,
  });
  context.after(() => rm(root, { recursive: true, force: true }));

  const violations = await findClientBoundaryViolations(root);

  assert.equal(violations.length, 2);
  assert.ok(violations.some((violation) => violation.includes('Search.tsx:2: imports ../server/repo/campgroundSearchRepo.js')));
  assert.ok(violations.some((violation) => violation.includes('Aliased.tsx:2: imports @/server/service/campgrounds')));
});

test('rejects a client component reaching a relative path inside packages/db', async (context) => {
  const root = await fixture({
    'src/components/Deep.tsx': `${CLIENT}import { pois } from '../../../../packages/db/src/schema';`,
  });
  context.after(() => rm(root, { recursive: true, force: true }));

  assert.equal((await findClientBoundaryViolations(root)).length, 1);
});

test('rejects NEXT_PUBLIC_ in any file, server or client', async (context) => {
  const root = await fixture({
    'src/app/page.tsx': 'export const token = process.env.NEXT_PUBLIC_MAPBOX_TOKEN;',
    'src/server/db.ts': '\nexport const url = process.env.NEXT_PUBLIC_DATABASE_URL;',
  });
  context.after(() => rm(root, { recursive: true, force: true }));

  const violations = await findClientBoundaryViolations(root);

  assert.deepEqual(violations, [
    'apps/web/src/app/page.tsx:1: references NEXT_PUBLIC_MAPBOX_TOKEN',
    'apps/web/src/server/db.ts:2: references NEXT_PUBLIC_DATABASE_URL',
  ]);
});

test('rejects NEXT_PUBLIC_ in an env file', async (context) => {
  const root = await fixture({ '.env.production': 'NEXT_PUBLIC_TOKEN=abc\n' });
  context.after(() => rm(root, { recursive: true, force: true }));

  assert.deepEqual(await findClientBoundaryViolations(root), [
    'apps/web/.env.production:1: references NEXT_PUBLIC_TOKEN',
  ]);
});

test('allows a server component importing @roadtrip/db', async (context) => {
  const root = await fixture({
    'src/app/page.tsx': "import { pois } from '@roadtrip/db';",
  });
  context.after(() => rm(root, { recursive: true, force: true }));

  assert.deepEqual(await findClientBoundaryViolations(root), []);
});

test('allows a client component importing @roadtrip/domain and lookalikes', async (context) => {
  const root = await fixture({
    'src/components/Ok.tsx': `${CLIENT}import { amenity } from '@roadtrip/domain';
import { x } from '@roadtrip/db-tools';
import { y } from './packages/db';
import { z } from '../../../../packages/db-tools/src';
const a = await import(\`@roadtrip/db-tools\${name}\`);`,
    'src/components/packages/db/index.ts': 'export const y = 1;',
  });
  context.after(() => rm(root, { recursive: true, force: true }));

  assert.deepEqual(await findClientBoundaryViolations(root), []);
});

test('allows a server-only module imported by another server module', async (context) => {
  const root = await fixture({
    'src/app/page.tsx': "import { list } from '../server/service/campgrounds.js';",
    'src/server/service/campgrounds.ts': "import { pois } from '@roadtrip/db';",
  });
  context.after(() => rm(root, { recursive: true, force: true }));

  assert.deepEqual(await findClientBoundaryViolations(root), []);
});

test('treats only a leading directive as marking a client component', async (context) => {
  const root = await fixture({
    'src/components/Late.tsx': "import { a } from './a';\n'use client';\nimport { pois } from '@roadtrip/db';",
    'src/components/Quoted.tsx': "const note = \"'use client'\";\nimport { pois } from '@roadtrip/db';",
    'src/components/a.ts': "import { pois } from '@roadtrip/db';",
  });
  context.after(() => rm(root, { recursive: true, force: true }));

  assert.deepEqual(await findClientBoundaryViolations(root), []);
});

test('recognises the directive after comments, in either quote style, with or without a semicolon', async (context) => {
  const root = await fixture({
    'src/components/Double.tsx': '"use client";\nimport { p } from \'@roadtrip/db\';',
    'src/components/Bare.tsx': "'use client'\nimport { p } from '@roadtrip/db';",
    'src/components/Commented.tsx': "// header\n\n/* block\n   comment */\n'use client';\nimport { p } from '@roadtrip/db';",
  });
  context.after(() => rm(root, { recursive: true, force: true }));

  assert.equal((await findClientBoundaryViolations(root)).length, 3);
});

test('ignores unresolvable specifiers, cycles, and skipped directories', async (context) => {
  const root = await fixture({
    'src/components/A.tsx': `${CLIENT}import { b } from './B';\nimport { gone } from './missing';\nimport { n } from 'next/link';`,
    'src/components/B.tsx': "import { a } from './A';",
    'node_modules/pkg/index.js': "require('@roadtrip/db'); process.env.NEXT_PUBLIC_X;",
    '.next/server/chunk.js': "import '@roadtrip/db'; process.env.NEXT_PUBLIC_X;",
  });
  context.after(() => rm(root, { recursive: true, force: true }));

  assert.deepEqual(await findClientBoundaryViolations(root), []);
});

test('rejects magic-comment, backtick and unspaced specifiers reaching @roadtrip/db', async (context) => {
  const root = await fixture({
    'src/components/Magic.tsx': `${CLIENT}const db = await import(/* webpackChunkName: "db" */ '@roadtrip/db');`,
    'src/components/Backtick.tsx': `${CLIENT}const db = await import(\`@roadtrip/db\`);`,
    'src/components/Compact.tsx': `${CLIENT}import{pois}from'@roadtrip/db';`,
    'src/components/LineComment.tsx': `${CLIENT}const db = await import(// split\n  '@roadtrip/db');`,
    'src/components/StrayQuote.tsx': `${CLIENT}// loaded via import "\nimport { pois } from '@roadtrip/db';`,
  });
  context.after(() => rm(root, { recursive: true, force: true }));

  const violations = await findClientBoundaryViolations(root);

  assert.equal(violations.length, 5);
  for (const name of ['Magic', 'Backtick', 'Compact', 'LineComment', 'StrayQuote']) {
    assert.ok(violations.some((violation) => violation.includes(`/${name}.tsx:`)), name);
  }
});

test('follows a magic-comment dynamic import of a relative helper chain', async (context) => {
  const root = await fixture({
    'src/components/Lazy.tsx': `${CLIENT}const rows = await import(/* webpackChunkName: "rows" */ './lib/rows');`,
    'src/components/lib/rows.ts': "import{pois}from'@roadtrip/db';",
  });
  context.after(() => rm(root, { recursive: true, force: true }));

  assert.deepEqual(await findClientBoundaryViolations(root), [
    'apps/web/src/components/lib/rows.ts:1: imports @roadtrip/db (reached from client component apps/web/src/components/Lazy.tsx)',
  ]);
});

test('follows a backtick dynamic import of a relative helper chain', async (context) => {
  const root = await fixture({
    'src/components/Tick.tsx': `${CLIENT}const rows = await import(\`./lib/rows\`);`,
    'src/components/lib/rows.ts': "import{pois}from'@roadtrip/db';",
  });
  context.after(() => rm(root, { recursive: true, force: true }));

  assert.deepEqual(await findClientBoundaryViolations(root), [
    'apps/web/src/components/lib/rows.ts:1: imports @roadtrip/db (reached from client component apps/web/src/components/Tick.tsx)',
  ]);
});

test('rejects a template specifier that begins with @roadtrip/db and interpolates the rest', async (context) => {
  const root = await fixture({
    'src/components/Template.tsx': `${CLIENT}const db = await import(\`@roadtrip/db\${name}\`);`,
  });
  context.after(() => rm(root, { recursive: true, force: true }));

  assert.equal((await findClientBoundaryViolations(root)).length, 1);
});

test('scans a long run of adjacent block comments before the hard timeout', async () => {
  const source = `import ${'/**/'.repeat(ADJACENT_BLOCK_COMMENTS)} nothing`;

  const { found } = await scanInWorker(source);

  assert.equal(found, 0);
});

test('reads block comments that contain stars, slashes and doubled terminators', () => {
  const source = [
    "import(/**/ '@roadtrip/a');",
    "import(/***/ '@roadtrip/b');",
    "import(/* a * b / c */ '@roadtrip/c');",
    "import(/** doc **/ '@roadtrip/d');",
    "import(/* one */ /* two */ '@roadtrip/e');",
  ].join('\n');

  assert.deepEqual(
    findModuleSpecifiers(source).map((found) => found.specifier),
    ['@roadtrip/a', '@roadtrip/b', '@roadtrip/c', '@roadtrip/d', '@roadtrip/e'],
  );
});

test('recognises the directive after a byte order mark, comments and blank lines', async (context) => {
  const root = await fixture({
    'src/components/Preceded.tsx': "\uFEFF// header\n\n\n/* block\n   comment */\n\n'use client';\nimport { pois } from '@roadtrip/db';",
  });
  context.after(() => rm(root, { recursive: true, force: true }));

  assert.equal((await findClientBoundaryViolations(root)).length, 1);
});

test('does not read NEXT_PUBLIC_ in prose files', async (context) => {
  const root = await fixture({
    'README.md': 'Never use NEXT_PUBLIC_ names here.',
    'docs/notes.mdx': 'NEXT_PUBLIC_TOKEN is forbidden.',
  });
  context.after(() => rm(root, { recursive: true, force: true }));

  assert.deepEqual(await findClientBoundaryViolations(root), []);
});

const SERVICE_FILE = 'src/server/service/booking/x.ts';
const REPO_RULE = { name: 'service', from: ['server/service/'], forbid: ['server/repo/'] };

async function layerFixture(context, files) {
  const root = await fixture(files);
  context.after(() => rm(root, { recursive: true, force: true }));
  return root;
}

test('a forbidden directory import is reported with its rule name', async (context) => {
  const root = await layerFixture(context, {
    [SERVICE_FILE]: "const a = 1;\nimport { r } from '@/server/repo/bookings';",
    'src/server/repo/bookings.ts': 'export const r = 1;',
    'src/server/service/booking/ok.ts': "import { r } from '@/server/contract';",
    'src/server/contract.ts': 'export const c = 1;',
  });

  const violations = await findLayerViolations(root, [REPO_RULE]);

  assert.deepEqual(violations, [
    'apps/web/src/server/service/booking/x.ts:2: imports @/server/repo/bookings (service may not import server/repo/)',
  ]);
});

test('a type-only import counts', async (context) => {
  const root = await layerFixture(context, {
    [SERVICE_FILE]: "import type { X } from '@/server/contract';",
    'src/server/contract.ts': 'export type X = string;',
  });

  const violations = await findLayerViolations(root, [
    { name: 'service', from: ['server/service/'], forbid: ['server/contract'] },
  ]);

  assert.equal(violations.length, 1);
  assert.ok(violations[0].includes('imports @/server/contract (service may not import server/contract)'));
});

test('an alias specifier and a relative one reach the same target', async (context) => {
  const root = await layerFixture(context, {
    'src/server/service/booking/a.ts': "import { r } from '@/server/repo/bookings';",
    'src/server/service/booking/b.ts': "import { r } from '../../repo/bookings';",
    'src/server/service/booking/c.ts': "import { r } from '../../repo/missing';",
    'src/server/repo/bookings.ts': 'export const r = 1;',
    'src/server/repo/bookingsHelper.ts': 'export const h = 1;',
    'src/server/service/booking/d.ts': "import { h } from '@/server/repo/bookingsHelper';",
  });

  const exactModule = await findLayerViolations(root, [
    { name: 'service', from: ['server/service/'], forbid: ['server/repo/bookings'] },
  ]);
  const directory = await findLayerViolations(root, [REPO_RULE]);

  assert.equal(exactModule.length, 2);
  assert.ok(exactModule.some((line) => line.startsWith('apps/web/src/server/service/booking/a.ts:1:')));
  assert.ok(exactModule.some((line) => line.startsWith('apps/web/src/server/service/booking/b.ts:1:')));
  assert.equal(directory.length, 4);
  assert.ok(directory.some((line) => line.includes('c.ts:1: imports ../../repo/missing')));
});

test('a forbidden package and its subpaths are reported', async (context) => {
  const root = await layerFixture(context, {
    'src/server/service/booking/a.ts': "import { headers } from 'next/headers';\nimport { sql } from 'drizzle-orm';\nimport x from 'nextra';",
  });

  const violations = await findLayerViolations(root, [
    { name: 'service', from: ['server/service/'], forbid: ['next', 'drizzle-orm'] },
  ]);

  assert.deepEqual(violations, [
    'apps/web/src/server/service/booking/a.ts:1: imports next/headers (service may not import next)',
    'apps/web/src/server/service/booking/a.ts:2: imports drizzle-orm (service may not import drizzle-orm)',
  ]);
});

test('an only rule reports anything outside its list', async (context) => {
  const root = await layerFixture(context, {
    'src/server/mapper/a.ts':
      "import { c } from '@/server/contract';\nimport { r } from '@/server/repo/bookings';\nimport fs from 'node:fs';\nimport { z } from 'zod';",
    'src/server/contract.ts': 'export const c = 1;',
    'src/server/repo/bookings.ts': 'export const r = 1;',
  });

  const violations = await findLayerViolations(root, [
    { name: 'mapper', from: ['server/mapper/'], only: ['server/contract', 'zod'] },
  ]);

  assert.deepEqual(violations, [
    'apps/web/src/server/mapper/a.ts:2: imports @/server/repo/bookings (mapper may import only server/contract, zod)',
    'apps/web/src/server/mapper/a.ts:3: imports node:fs (mapper may import only server/contract, zod)',
  ]);
});

test('a RegExp entry matches by resolved path', async (context) => {
  const root = await layerFixture(context, {
    'src/app/api/route.ts': "import { c } from '@/server/booking/BookingController';\nimport { d } from '@/server/booking/other';",
    'src/server/booking/BookingController.ts': 'export const c = 1;',
    'src/server/booking/other.ts': 'export const d = 1;',
  });

  const violations = await findLayerViolations(root, [
    { name: 'route', from: [/^app\/api\/.*route\.ts$/], forbid: [/Controller\.ts$/] },
  ]);

  assert.equal(violations.length, 1);
  assert.ok(violations[0].includes('route.ts:1: imports @/server/booking/BookingController (route may not import /Controller\\.ts$/)'));
});

test('test files are exempt', async (context) => {
  const root = await layerFixture(context, {
    'src/server/service/booking/x.test.ts': "import { r } from '@/server/repo/bookings';\nconst e = process.env.X;",
    'src/server/repo/bookings.ts': 'export const r = 1;',
  });

  assert.deepEqual(await findLayerViolations(root, [REPO_RULE]), []);
  assert.deepEqual(await findEnvReadViolations(root), []);
});

test('process.env is reported outside server/config.ts, and not in config.ts or a test', async (context) => {
  const root = await layerFixture(context, {
    'src/server/config.ts': 'export const url = process.env.DATABASE_URL;',
    'src/server/other.ts': 'const a = 1;\nexport const b = process.env.B;',
    'src/server/configuration.ts': 'export const c = process.env.C;',
    'src/server/other.test.ts': 'export const d = process.env.D;',
    'src/server/notes.md': 'process.env.E',
  });

  const violations = await findEnvReadViolations(root);

  assert.deepEqual(violations, [
    'apps/web/src/server/configuration.ts:1: reads process.env',
    'apps/web/src/server/other.ts:2: reads process.env',
  ]);
});

test('instrumentation.ts may read NEXT_RUNTIME, which Next.js inlines, and nothing else; no other file may', async (context) => {
  const root = await layerFixture(context, {
    'src/instrumentation.ts': "if (process.env.NEXT_RUNTIME === 'nodejs') {}\nexport const x = process.env.OTHER;",
    'src/server/runtime.ts': "export const node = process.env.NEXT_RUNTIME === 'nodejs';",
  });

  assert.deepEqual(await findEnvReadViolations(root), [
    'apps/web/src/instrumentation.ts:2: reads process.env',
    'apps/web/src/server/runtime.ts:1: reads process.env',
  ]);
});

test('process.env is reported in each of its ordinary spellings', async (context) => {
  const spellings = {
    'src/server/a.ts': "const key = process['env'].A;",
    'src/server/b.ts': 'const { env } = process;',
    'src/server/c.ts': "import { env } from 'node:process';",
    'src/server/d.ts': "const env = require('process').env;",
    'src/server/e.ts': 'const key = process?.env.E;',
    'src/server/f.ts': "const key = globalThis['process'];",
    'src/server/g.ts': 'const runtime = process;',
    'src/server/h.ts': 'const key = globalThis.process.env.H;',
    'src/server/i.ts': 'const key = (process as unknown as { env: Record<string, string> }).env.I;',
    'src/server/j.ts': 'const key = process!.env.J;',
    'src/server/k.ts': "import 'node:process';",
    'src/server/l.ts': 'const { env } = process as NodeJS.Process;',
    'src/server/m.ts': 'const key = process[NAME];',
    'src/server/n.ts': "const env = (await import('node:process')).env;",
  };
  const root = await layerFixture(context, spellings);

  const violations = await findEnvReadViolations(root);

  assert.deepEqual(
    violations,
    Object.keys(spellings).map((file) => `apps/web/${file}:1: reads process.env`),
  );
});

test('prose and names that only mention a process are not reported', async (context) => {
  const root = await layerFixture(context, {
    'src/server/copy.ts': "export const copy = 'We could not process your request, so nothing was saved.';",
    'src/server/names.ts': 'export const processing = 1;\nexport function processQueue(process_id: number) { return process_id; }',
    'src/server/kinds.ts': "export const step = { kind: 'process' };\nexport type Phase = 'process' | 'done';\nexport const isStep = (kind: string) => kind === \"process\";",
  });

  assert.deepEqual(await findEnvReadViolations(root), []);
});

test('a file a rule excepts is skipped while the rest of its directory is checked', async (context) => {
  const root = await layerFixture(context, {
    'src/server/service/booking/legacy.ts': "import { r } from '@/server/repo/bookings';",
    [SERVICE_FILE]: "import { r } from '@/server/repo/bookings';",
    'src/server/repo/bookings.ts': 'export const r = 1;',
  });

  const violations = await findLayerViolations(root, [{ ...REPO_RULE, except: ['server/service/booking/legacy.ts'] }]);

  assert.deepEqual(violations, [
    'apps/web/src/server/service/booking/x.ts:1: imports @/server/repo/bookings (service may not import server/repo/)',
  ]);
});

test('the shipped table covers a file added to a layered directory', async (context) => {
  const root = await layerFixture(context, {
    'src/server/service/booking/holdController.ts': "import { r } from '@/server/repo/holds';",
    'src/server/service/settings/flow.ts': "import { rows } from '@roadtrip/db';",
    'src/server/service/settings/typesController.ts': "import type { X } from '../../../../../../frontend/src/api/generated/api-types';",
    'src/server/service/trips/model.ts': "import type { X } from '@/server/contract';",
    'src/server/service/trips/tripController.ts': "import { r } from '@/server/repo/holds';\nimport type { T } from './ports';",
    'src/server/service/trips/ports.ts': 'export type T = string;',
    'src/server/repo/holds.ts': "import type { X } from '@/server/contract';",
    'src/server/repo/typed.ts': "import type { X } from '../../../../../frontend/src/api/generated/api-types';",
    'src/server/client/holds.ts': "import { r } from '@/server/repo/holds';",
    'src/server/mapper/http/who.ts': "import { currentAppUser } from '@/server/auth/currentUser';",
    'src/server/mapper/relay/newWire.ts': "import { config } from '@/server/config';",
    'src/server/mapper/row/newRow.ts': "import type { X } from '../../../../../../frontend/src/api/generated/api-types';",
    'src/app/api/og/route.tsx': "import { r } from '@/server/repo/holds';",
    'src/server/auth/currentUser.ts': 'export const currentAppUser = 1;',
    'src/server/contract.ts': 'export type X = string;',
    'src/app/api/trips/route.ts': "import { db } from '@/server/db';",
    'src/app/(account)/api/holds/route.ts': "import { config } from '@/server/config';",
    'src/server/db.ts': 'export const db = 1;',
    'src/server/config.ts': 'export const config = 1;',
  });

  const reported = (await findLayerViolations(root)).map((line) => line.replace(/:\d+: imports \S+ \((.+?) may.*$/, ' -> $1'));

  assert.deepEqual(reported.sort(), [
    'apps/web/src/app/(account)/api/holds/route.ts -> route',
    'apps/web/src/app/api/og/route.tsx -> route',
    'apps/web/src/app/api/trips/route.ts -> route',
    'apps/web/src/server/client/holds.ts -> client',
    'apps/web/src/server/mapper/http/who.ts -> http mapper',
    'apps/web/src/server/mapper/relay/newWire.ts -> relay mapper',
    'apps/web/src/server/mapper/row/newRow.ts -> row mapper',
    'apps/web/src/server/repo/holds.ts -> repository',
    'apps/web/src/server/repo/typed.ts -> repository',
    'apps/web/src/server/service/booking/holdController.ts -> controller',
    'apps/web/src/server/service/settings/flow.ts -> controller',
    'apps/web/src/server/service/settings/typesController.ts -> controller',
    'apps/web/src/server/service/trips/model.ts -> model',
    'apps/web/src/server/service/trips/tripController.ts -> controller',
  ]);
});

test('the shipped table passes on this repository', async () => {
  assert.deepEqual(await findLayerViolations(REPO_ROOT), []);
  assert.deepEqual(await findEnvReadViolations(REPO_ROOT), []);
});

test('the shipped table names every rule the spec lists', () => {
  assert.deepEqual(LAYER_RULES.map((rule) => rule.name).sort(), [
    'availability matrix',
    'client',
    'controller',
    'http mapper',
    'model',
    'relay mapper',
    'repository',
    'route',
    'row mapper',
  ]);
});

test('every path in the shipped table names something that exists', () => {
  const missing = [];
  for (const rule of LAYER_RULES) {
    for (const entry of [...(rule.from ?? []), ...(rule.forbid ?? []), ...(rule.only ?? []), ...(rule.except ?? [])]) {
      if (typeof entry !== 'string' || !PATH_ENTRY_PREFIXES.some((prefix) => entry.startsWith(prefix))) continue;
      const target = path.join(WEB_SOURCE_ROOT, entry);
      const exists = entry.endsWith(DIRECTORY_ENTRY_SUFFIX) ? isDirectory(target) : candidatePaths(target).some(isFile);
      if (!exists) missing.push(`${rule.name}: ${entry}`);
    }
  }
  assert.deepEqual(missing, []);
});

test('every file the shipped table excepts still breaks its rule', async () => {
  const stale = [];
  for (const rule of LAYER_RULES) {
    if (!rule.except) continue;
    const { except, ...whole } = rule;
    const violations = await findLayerViolations(REPO_ROOT, [whole]);
    for (const entry of except) {
      if (!violations.some((line) => line.startsWith(`apps/web/src/${entry}:`))) stale.push(`${rule.name}: ${entry}`);
    }
  }
  assert.deepEqual(stale, []);
});
