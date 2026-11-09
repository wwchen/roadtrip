import { readdirSync, readFileSync, statSync } from 'node:fs';
import path from 'node:path';
import { expect, test } from 'vitest';

const SOURCE_ROOT = path.resolve(import.meta.dirname, '../..');
const PATHS_MODULE = path.join(SOURCE_ROOT, 'server/auth/authPaths.ts');
const AUTH_PAGE_LITERAL = /['"`]\/auth\/(sign-in|sign-up|forgot|reset)\b/;
const REDEFINED_CONSTANT = /const (SIGN_IN_PATH|UNKNOWN_FAILURE|NETWORK_CODE_PREFIX) =/;
const SOURCE_FILE = /\.tsx?$/;
const TEST_FILE = /\.test\.tsx?$/;

function sourceFiles(dir: string): string[] {
  return readdirSync(dir).flatMap((entry) => {
    const full = path.join(dir, entry);
    if (statSync(full).isDirectory()) return sourceFiles(full);
    return SOURCE_FILE.test(entry) && !TEST_FILE.test(entry) ? [full] : [];
  });
}

test('auth page paths are spelled only in authPaths.ts', () => {
  const offenders = sourceFiles(SOURCE_ROOT)
    .filter((file) => file !== PATHS_MODULE)
    .filter((file) => AUTH_PAGE_LITERAL.test(readFileSync(file, 'utf8')))
    .map((file) => path.relative(SOURCE_ROOT, file));
  expect(offenders).toEqual([]);
});

test('shared auth constants are defined once', () => {
  const definitions = sourceFiles(SOURCE_ROOT).flatMap((file) =>
    [...readFileSync(file, 'utf8').matchAll(new RegExp(REDEFINED_CONSTANT, 'g'))].map((match) => match[1]),
  );
  expect(definitions.sort()).toEqual(['NETWORK_CODE_PREFIX', 'SIGN_IN_PATH', 'UNKNOWN_FAILURE']);
});
