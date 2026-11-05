#!/usr/bin/env node
import { access, cp, mkdir, readdir, rm } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const REPO_ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..', '..');
const WEB_ROOT = path.join(REPO_ROOT, 'apps', 'web');

export const FRONTEND_DIST = 'frontend/dist';
export const PUBLIC_DIR = 'public';
export const STATIC_DATA_FILES = ['data/us-states.geojson'];

async function filesUnder(dir) {
  const entries = await readdir(dir, { recursive: true, withFileTypes: true });
  return entries.filter((entry) => entry.isFile()).map((entry) => path.relative(dir, path.join(entry.parentPath, entry.name)));
}

export async function copyFrontend({ distDir, dataFiles, publicDir }) {
  try {
    await access(distDir);
  } catch {
    throw new Error(`no Vite build at ${distDir}; build the frontend first (npm run build at the repository root)`);
  }
  await rm(publicDir, { recursive: true, force: true });
  await mkdir(publicDir, { recursive: true });
  await cp(distDir, publicDir, { recursive: true });
  const copied = await filesUnder(distDir);
  for (const { from, to } of dataFiles) {
    await mkdir(path.dirname(path.join(publicDir, to)), { recursive: true });
    await cp(from, path.join(publicDir, to));
    copied.push(to);
  }
  return copied;
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const copied = await copyFrontend({
    distDir: path.join(REPO_ROOT, FRONTEND_DIST),
    dataFiles: STATIC_DATA_FILES.map((file) => ({ from: path.join(REPO_ROOT, file), to: file })),
    publicDir: path.join(WEB_ROOT, PUBLIC_DIR),
  });
  console.log(`copied ${copied.length} frontend files into apps/web/${PUBLIC_DIR}`);
}
