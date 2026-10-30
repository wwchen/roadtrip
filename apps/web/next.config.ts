import path from 'node:path';
import { fileURLToPath } from 'node:url';
import type { NextConfig } from 'next';

const WORKSPACE_ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');

const config: NextConfig = {
  outputFileTracingRoot: WORKSPACE_ROOT,
};

export default config;
