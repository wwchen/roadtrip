import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { defineConfig } from 'vitest/config';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const WORKER = path.resolve(HERE, '../../packages/worker');

export default defineConfig({
  root: WORKER,
  test: {
    include: ['src/vendors/**/*.test.ts'],
    setupFiles: [path.join(HERE, 'record-setup.mjs')],
    fileParallelism: false,
  },
});
