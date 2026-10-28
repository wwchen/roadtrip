import assert from 'node:assert/strict';
import { mkdtemp, mkdir, rm, writeFile } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';
import { findClientBoundaryViolations } from './check-server-only-imports.mjs';

const CLIENT = "'use client';\n";

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
import { z } from '../../../../packages/db-tools/src';`,
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
