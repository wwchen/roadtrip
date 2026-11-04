import assert from 'node:assert/strict';
import { mkdir, mkdtemp, readdir, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';
import test from 'node:test';
import { copyFrontend, STATIC_DATA_FILES } from './copy-frontend.mjs';

async function tree() {
  const root = await mkdtemp(path.join(tmpdir(), 'copy-frontend-'));
  const distDir = path.join(root, 'dist');
  const publicDir = path.join(root, 'public');
  await mkdir(path.join(distDir, 'assets'), { recursive: true });
  await mkdir(path.join(root, 'data'), { recursive: true });
  await writeFile(path.join(distDir, 'index.html'), '<html>map</html>');
  await writeFile(path.join(distDir, 'poi.html'), '<html>poi</html>');
  await writeFile(path.join(distDir, 'assets', 'x.js'), 'x');
  await writeFile(path.join(root, 'data', 'us-states.geojson'), '{}');
  return { root, distDir, publicDir, dataFiles: STATIC_DATA_FILES.map((file) => ({ from: path.join(root, file), to: file })) };
}

async function listed(dir) {
  const entries = await readdir(dir, { recursive: true, withFileTypes: true });
  return entries
    .filter((entry) => entry.isFile())
    .map((entry) => path.relative(dir, path.join(entry.parentPath, entry.name)))
    .sort();
}

test('copies every file of the Vite build and the static data files under public', async () => {
  const { distDir, publicDir, dataFiles } = await tree();
  const copied = await copyFrontend({ distDir, dataFiles, publicDir });
  const expected = ['assets/x.js', 'data/us-states.geojson', 'index.html', 'poi.html'];
  assert.deepEqual(await listed(publicDir), expected);
  assert.deepEqual([...copied].sort(), expected);
});

test('refuses when the Vite build is missing, naming the directory', async () => {
  const { root, publicDir, dataFiles } = await tree();
  const missing = path.join(root, 'nope');
  await assert.rejects(copyFrontend({ distDir: missing, dataFiles, publicDir }), new RegExp(`${missing}.*npm run build`));
});

test('empties public first, so a removed asset does not survive', async () => {
  const { distDir, publicDir, dataFiles } = await tree();
  await mkdir(path.join(publicDir, 'assets'), { recursive: true });
  await writeFile(path.join(publicDir, 'assets', 'stale.js'), 'old');
  await copyFrontend({ distDir, dataFiles, publicDir });
  assert.ok(!(await listed(publicDir)).includes('assets/stale.js'));
});
