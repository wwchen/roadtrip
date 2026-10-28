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
