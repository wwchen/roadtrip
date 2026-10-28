import { createRequire } from 'node:module';
import path from 'node:path';
import { defineConfig } from 'vitest/config';

const CONTAINER_TIMEOUT_MS = 600_000;
const SERVER_ONLY_PACKAGE = 'server-only';
const SERVER_ONLY_NOOP = 'empty.js';

const serverOnlyNoop = path.join(
  path.dirname(createRequire(import.meta.url).resolve(SERVER_ONLY_PACKAGE)),
  SERVER_ONLY_NOOP,
);

export default defineConfig({
  resolve: { alias: { [SERVER_ONLY_PACKAGE]: serverOnlyNoop } },
  test: {
    testTimeout: CONTAINER_TIMEOUT_MS,
    hookTimeout: CONTAINER_TIMEOUT_MS,
    fileParallelism: false,
  },
});
