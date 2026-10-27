import assert from 'node:assert/strict';
import { existsSync } from 'node:fs';
import test from 'node:test';
import { MEMBER_DIRECTORIES, memberLockfileReferences } from './check-single-lockfile.mjs';

test('exactly one lockfile exists, at the repo root', () => {
  assert.ok(existsSync('package-lock.json'));
  for (const member of MEMBER_DIRECTORIES) {
    assert.equal(existsSync(`${member}/package-lock.json`), false, `${member} has its own lockfile`);
  }
});

test('no tracked file references a member lockfile', () => {
  assert.deepEqual(memberLockfileReferences(), []);
});
