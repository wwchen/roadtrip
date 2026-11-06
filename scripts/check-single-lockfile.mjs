import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const REPO_ROOT = fileURLToPath(new URL('..', import.meta.url));
const LOCKFILE = 'package-lock.json';
const MEMBER_LOCKFILE_REFERENCE = /(?:frontend|companion)\/package-lock\.json/;
const EXEMPT_PATHSPECS = [':(exclude)docs/superpowers', ':(exclude)scripts/check-single-lockfile*'];
const GIT_GREP_NO_MATCH = 1;

export const MEMBER_DIRECTORIES = ['frontend', 'companion'];

function grepTracked(pattern, repoRoot) {
  try {
    return execFileSync('git', ['grep', '-n', '-I', '-F', pattern, '--', '.', ...EXEMPT_PATHSPECS], {
      cwd: repoRoot,
      encoding: 'utf8',
    })
      .split('\n')
      .filter(Boolean);
  } catch (error) {
    if (error.status === GIT_GREP_NO_MATCH) return [];
    throw error;
  }
}

export function memberLockfileReferences(repoRoot = REPO_ROOT) {
  return grepTracked(LOCKFILE, repoRoot).filter((line) => MEMBER_LOCKFILE_REFERENCE.test(line));
}
