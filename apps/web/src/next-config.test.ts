import { existsSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { expect, test } from 'vitest';
import config from '../next.config';

const REPO_ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..', '..');
const ROOT_MANIFEST = path.join(REPO_ROOT, 'package.json');

test('outputFileTracingRoot is the repository root that holds the hoisted node_modules', () => {
  expect(config.outputFileTracingRoot).toBe(REPO_ROOT);
  expect(existsSync(ROOT_MANIFEST)).toBe(true);
  expect(existsSync(path.join(REPO_ROOT, 'node_modules', 'next'))).toBe(true);
});

test('/ is rewritten to /index.html before the app routes', async () => {
  const rewrites = await config.rewrites?.();
  expect(rewrites).toMatchObject({ beforeFiles: expect.arrayContaining([{ source: '/', destination: '/index.html' }]) });
});

test('the extensionless page URLs the Kotlin site served still resolve', async () => {
  const rewrites = await config.rewrites?.();
  const beforeFiles = (rewrites as { beforeFiles: { source: string; destination: string }[] }).beforeFiles;
  expect(beforeFiles).toEqual(expect.arrayContaining([{ source: '/poi', destination: '/poi.html' }]));
});
