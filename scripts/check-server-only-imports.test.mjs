import assert from 'node:assert/strict';
import { mkdtemp, mkdir, rm, writeFile } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';
import { Worker } from 'node:worker_threads';
import { findClientBoundaryViolations } from './check-server-only-imports.mjs';
import { findModuleSpecifiers } from './module-specifiers.mjs';

const CLIENT = "'use client';\n";
const ADJACENT_BLOCK_COMMENTS = 64;
const SCAN_BUDGET_MS = 100;
const SCAN_HARD_TIMEOUT_MS = 3000;
const MODULE_SPECIFIERS_URL = new URL('./module-specifiers.mjs', import.meta.url).href;
const SCAN_WORKER = `
const { parentPort, workerData } = require('node:worker_threads');
import(workerData.moduleUrl).then(({ findModuleSpecifiers }) => {
  const started = performance.now();
  const found = findModuleSpecifiers(workerData.source);
  parentPort.postMessage({ elapsed: performance.now() - started, found: found.length });
});
`;

function scanInWorker(source) {
  return new Promise((resolve, reject) => {
    const worker = new Worker(SCAN_WORKER, { eval: true, workerData: { moduleUrl: MODULE_SPECIFIERS_URL, source } });
    const timer = setTimeout(() => {
      worker.terminate();
      reject(new Error(`scan did not finish within ${SCAN_HARD_TIMEOUT_MS}ms`));
    }, SCAN_HARD_TIMEOUT_MS);
    worker.once('error', reject);
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

test('scans a long run of adjacent block comments inside the time budget', async () => {
  const source = `import ${'/**/'.repeat(ADJACENT_BLOCK_COMMENTS)} nothing`;

  const { elapsed, found } = await scanInWorker(source);

  assert.equal(found, 0);
  assert.ok(elapsed < SCAN_BUDGET_MS, `scan took ${elapsed}ms, budget ${SCAN_BUDGET_MS}ms`);
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
