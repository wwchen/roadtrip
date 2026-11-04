import path from 'node:path';
import { fileURLToPath } from 'node:url';
import type { NextConfig } from 'next';

const WORKSPACE_ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const MAP_PAGE = '/index.html';

const config: NextConfig = {
  outputFileTracingRoot: WORKSPACE_ROOT,
  env: {
    BUILD_ENV: process.env.VERCEL_ENV ?? process.env.BUILD_ENV ?? '',
    BUILD_SHA: process.env.VERCEL_GIT_COMMIT_SHA ?? process.env.BUILD_SHA ?? '',
    BUILD_BRANCH: process.env.VERCEL_GIT_COMMIT_REF ?? process.env.BUILD_BRANCH ?? '',
  },
  rewrites: async () => ({ beforeFiles: [{ source: '/', destination: MAP_PAGE }], afterFiles: [], fallback: [] }),
};

export default config;
