import { spawnSync } from 'node:child_process';
import { mkdtempSync, rmSync, symlinkSync } from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { afterAll, beforeAll, describe, expect, test } from 'vitest';

const PACKAGE_DIR = fileURLToPath(new URL('../..', import.meta.url));
const BUILT_CLI = path.join(PACKAGE_DIR, 'dist', 'cli', 'import.js');
const BUILD_TIMEOUT_MS = 120_000;
const EXIT_OK = 0;
const REPO_ROOT = path.resolve(PACKAGE_DIR, '..', '..');
const CLI_ARGS = ['--list', '--raw-dir', path.join(REPO_ROOT, 'data', 'raw'), '--static-dir', REPO_ROOT];
const IMPLEMENTED_TARGETS = ['poi_data:Planet Fitness', 'poi_data:Tesla Superchargers'];

let linkDir = '';
let symlinkedCli = '';

function runBuilt(entry: string): { status: number | null; stdout: string; stderr: string } {
  const result = spawnSync(process.execPath, [entry, ...CLI_ARGS], { cwd: PACKAGE_DIR, encoding: 'utf8' });
  return { status: result.status, stdout: result.stdout, stderr: result.stderr };
}

beforeAll(() => {
  const build = spawnSync('npm', ['run', 'build'], { cwd: PACKAGE_DIR, encoding: 'utf8' });
  if (build.status !== 0) throw new Error(`worker build failed: ${build.stdout}${build.stderr}`);
  linkDir = mkdtempSync(path.join(os.tmpdir(), 'import-bin-'));
  symlinkedCli = path.join(linkDir, 'roadtrip-import');
  symlinkSync(BUILT_CLI, symlinkedCli);
}, BUILD_TIMEOUT_MS);

afterAll(() => {
  rmSync(linkDir, { recursive: true, force: true });
});

describe('built import cli', () => {
  test('run by its real path, it lists the targets whose adapters the build registers', () => {
    const result = runBuilt(BUILT_CLI);
    expect(result.stderr).toBe('');
    expect(result.status).toBe(EXIT_OK);
    expect(result.stdout.split('\n')).toEqual(expect.arrayContaining(IMPLEMENTED_TARGETS));
  });

  test('run through a symlink, as an installed bin is, it behaves identically', () => {
    const result = runBuilt(symlinkedCli);
    expect(result.stderr).toBe('');
    expect(result.status).toBe(EXIT_OK);
    expect(result.stdout).toBe(runBuilt(BUILT_CLI).stdout);
  });
});
