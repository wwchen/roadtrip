import { spawnSync } from 'node:child_process';
import { mkdtempSync, rmSync, symlinkSync } from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { afterAll, beforeAll, describe, expect, test } from 'vitest';

const PACKAGE_DIR = fileURLToPath(new URL('../..', import.meta.url));
const BUILT_CLI = path.join(PACKAGE_DIR, 'dist', 'cli', 'import.js');
const BUILD_TIMEOUT_MS = 120_000;
const EXIT_FAILED = 1;
const EMPTY_REGISTRY_MESSAGE = 'no adapters registered';
const CLI_ARGS = ['--list', '--raw-dir', 'data/raw', '--static-dir', '.'];

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
  test('run by its real path, an empty adapter registry exits non-zero with the message', () => {
    const result = runBuilt(BUILT_CLI);
    expect(result.status).toBe(EXIT_FAILED);
    expect(result.stderr).toContain(EMPTY_REGISTRY_MESSAGE);
    expect(result.stdout).toBe('');
  });

  test('run through a symlink, as an installed bin is, it behaves identically', () => {
    const result = runBuilt(symlinkedCli);
    expect(result.status).toBe(EXIT_FAILED);
    expect(result.stderr).toContain(EMPTY_REGISTRY_MESSAGE);
    expect(result.stdout).toBe('');
  });
});
