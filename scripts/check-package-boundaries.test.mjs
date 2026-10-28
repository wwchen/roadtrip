import assert from 'node:assert/strict';
import { mkdtemp, mkdir, rm, writeFile } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';
import { findPackageBoundaryViolations } from './check-package-boundaries.mjs';

async function fixture(files, manifest = { dependencies: { '@roadtrip/domain': '*' } }) {
  const root = await mkdtemp(path.join(os.tmpdir(), 'roadtrip-packages-'));
  const all = { 'package.json': JSON.stringify(manifest), ...files };
  await Promise.all(
    Object.entries(all).map(async ([relative, source]) => {
      const target = path.join(root, 'frontend', relative);
      await mkdir(path.dirname(target), { recursive: true });
      await writeFile(target, source);
    }),
  );
  return root;
}

test('rejects every import form that reaches @roadtrip/db', async (context) => {
  const root = await fixture({
    'src/static.ts': "import { schema } from '@roadtrip/db';",
    'src/reexport.ts': "export { campgrounds } from '@roadtrip/db';",
    'src/subpath.ts': "import { client } from '@roadtrip/db/client';",
    'src/dynamic.ts': "const db = await import('@roadtrip/db');",
    'src/required.cjs': "const db = require('@roadtrip/db');",
    'src/magic.ts': "const db = await import(/* webpackChunkName: \"db\" */ '@roadtrip/db');",
    'src/backtick.ts': 'const db = await import(`@roadtrip/db`);',
    'src/compact.ts': "import{schema}from'@roadtrip/db';",
    'src/stray-quote.ts': "// loaded via import \"\nimport { schema } from '@roadtrip/db';",
    'src/Thing.test.tsx': "import { schema } from '@roadtrip/db';",
    'vite.config.ts': "import { schema } from '@roadtrip/db';",
    '.storybook/main.ts': "import { schema } from '@roadtrip/db';",
  });
  context.after(() => rm(root, { recursive: true, force: true }));

  const violations = await findPackageBoundaryViolations(root);

  assert.equal(violations.length, 12);
  assert.ok(violations.every((violation) => violation.includes('imports @roadtrip/db')));
});

test('rejects relative paths that reach into packages/db', async (context) => {
  const root = await fixture({
    'src/shallow.ts': "import { schema } from '../../packages/db/src/schema';",
    'src/lib/deep.ts': "export * from '../../../packages/db';",
    'src/lib/dynamic.ts': "const db = await import('../../../packages/db/dist/index.js');",
    'src/lib/magic.ts': "const db = await import(/* webpackChunkName: \"db\" */ '../../../packages/db');",
  });
  context.after(() => rm(root, { recursive: true, force: true }));

  const violations = await findPackageBoundaryViolations(root);

  assert.equal(violations.length, 4);
  assert.ok(violations.every((violation) => violation.includes('imports ')));
});

test('rejects declaring @roadtrip/db as a frontend dependency', async (context) => {
  const root = await fixture({}, { devDependencies: { '@roadtrip/db': '*' } });
  context.after(() => rm(root, { recursive: true, force: true }));

  const violations = await findPackageBoundaryViolations(root);

  assert.equal(violations.length, 1);
  assert.match(violations[0], /package\.json: devDependencies declares @roadtrip\/db/);
});

test('allows @roadtrip/domain, lookalike names and installed packages', async (context) => {
  const root = await fixture({
    'src/ok.ts': "import { amenity } from '@roadtrip/domain';",
    'src/lookalike.ts': "import { x } from '@roadtrip/db-tools';",
    'src/sibling.ts': "import { x } from '../../packages/db-tools/src';",
    'src/domain.ts': "import { x } from '../../packages/domain/src';",
    'src/local/index.ts': "import { x } from '../packages/db';",
    'src/packages/db/index.ts': "export const x = 1;",
    'node_modules/pkg/index.js': "require('@roadtrip/db');",
    'dist/bundle.js': "import '@roadtrip/db';",
  });
  context.after(() => rm(root, { recursive: true, force: true }));

  assert.deepEqual(await findPackageBoundaryViolations(root), []);
});
