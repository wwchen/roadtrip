import assert from 'node:assert/strict';
import { mkdtemp, mkdir, rm, writeFile } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';
import { findBoundaryViolations } from './check-feature-boundaries.mjs';

async function fixture(files) {
  const root = await mkdtemp(path.join(os.tmpdir(), 'roadtrip-boundaries-'));
  await Promise.all(
    Object.entries(files).map(async ([relative, source]) => {
      const target = path.join(root, 'frontend/src', relative);
      await mkdir(path.dirname(target), { recursive: true });
      await writeFile(target, source);
    }),
  );
  for (const area of ['features', 'domain']) {
    await mkdir(path.join(root, 'frontend/src', area), { recursive: true });
  }
  return root;
}

test('rejects alias and relative imports across features', async (context) => {
  const root = await fixture({
    'features/map/alias.ts': "import { AlertsPanel } from '@/features/alerts/AlertsPanel';",
    'features/map/relative.ts': "export { AlertsPanel } from '../alerts/AlertsPanel';",
  });
  context.after(() => rm(root, { recursive: true, force: true }));

  const violations = await findBoundaryViolations(root);

  assert.equal(violations.length, 2);
  assert.ok(violations.every((violation) => violation.includes('features/map imports features/alerts')));
});

test('rejects domain-to-feature imports while allowing feature-to-domain imports', async (context) => {
  const root = await fixture({
    'domain/watch/editor.ts': "import { WatchTable } from '@/features/watches/WatchTable';",
    'features/watches/queries.ts': "import { watchListQuery } from '@/domain/watch/queries';",
  });
  context.after(() => rm(root, { recursive: true, force: true }));

  const violations = await findBoundaryViolations(root);

  assert.equal(violations.length, 1);
  assert.match(violations[0], /domain\/watch imports features\/watches/);
});
