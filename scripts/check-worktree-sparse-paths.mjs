#!/usr/bin/env node
/**
 * `.claude/settings.json` → `worktree.sparsePaths` guardrail.
 *
 * Claude Code runs `git sparse-checkout set --cone -- <paths>` when it creates a
 * worktree, so the list is CONE MODE: plain directory names only. Two failure
 * modes, both of which have already bitten us or would have:
 *
 *   1. A gitignore-style pattern. Cone mode rejects `/*` and `!/data/raw/` with
 *      "specify directories rather than patterns (no leading slash)", and Claude
 *      Code deletes the half-made worktree and aborts — so every worktree
 *      creation fails, not just the excluded path. That is what #703 shipped.
 *   2. Drift. Cone mode can only ENUMERATE what to include, never subtract, so
 *      the list has to name every directory we want. A new top-level directory
 *      that nobody adds here is simply missing from worktrees, and only shows
 *      up as a confusing "where did my code go" much later.
 *
 * So: every tracked directory is either reachable from `sparsePaths` or named in
 * EXCLUDED below, and every entry is a real directory spelled the way cone mode
 * wants it.
 *
 *   node scripts/check-worktree-sparse-paths.mjs
 */

import { execFileSync } from 'node:child_process';
import { existsSync, readFileSync, readdirSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const SETTINGS = '.claude/settings.json';

/**
 * Tracked directories deliberately kept out of Claude worktrees, each with the
 * reason it earns an exemption rather than an entry in `sparsePaths`.
 */
const EXCLUDED = new Map([
  // ~1.7 GB across ~19k append-only capture files. Agents never read them, and
  // copying them into every worktree is the disk bloat #703 set out to avoid.
  // `make data-import` and the fetchers run from the main checkout.
  ['data/raw', 'append-only raw captures, ~1.7 GB'],
]);

/** Cone mode takes `a` or `a/b`: no leading or trailing slash, no globs, no negation. */
const GLOB_OR_NEGATION = /[*?[\]!\\]/;
const isConeDirectory = (p) =>
  typeof p === 'string' &&
  p.length > 0 &&
  !GLOB_OR_NEGATION.test(p) &&
  p.split('/').every((segment) => segment.length > 0 && segment !== '.' && segment !== '..');

const git = (...args) =>
  execFileSync('git', args, { cwd: ROOT, encoding: 'utf8', maxBuffer: 64 * 1024 * 1024 })
    .split('\n')
    .filter(Boolean);

const settings = JSON.parse(readFileSync(resolve(ROOT, SETTINGS), 'utf8'));
const sparsePaths = settings.worktree?.sparsePaths;
if (!Array.isArray(sparsePaths) || sparsePaths.length === 0) {
  console.error(`${SETTINGS}: worktree.sparsePaths is missing or empty.`);
  process.exit(1);
}

const trackedDirs = new Set(git('ls-tree', '-d', '-r', '--name-only', 'HEAD'));
const childrenOf = (parent) => {
  const prefix = parent === '' ? '' : `${parent}/`;
  return [...trackedDirs].filter(
    (d) => d.startsWith(prefix) && !d.slice(prefix.length).includes('/'),
  );
};

// A directory is `full` when sparsePaths names it or an ancestor of it — cone
// mode writes the whole subtree. It is `partial` when sparsePaths names only
// something beneath it: its own files land, its unlisted subdirectories do not.
const isFull = (dir) => sparsePaths.some((p) => p === dir || dir.startsWith(`${p}/`));
const isPartial = (dir) => sparsePaths.some((p) => p.startsWith(`${dir}/`));

const malformed = sparsePaths.filter((p) => !isConeDirectory(p));
const unknown = sparsePaths.filter((p) => isConeDirectory(p) && !trackedDirs.has(p));

const omitted = [];
const walk = (parent) => {
  for (const dir of childrenOf(parent)) {
    if (isFull(dir)) continue;
    if (isPartial(dir)) walk(dir);
    else if (!EXCLUDED.has(dir)) omitted.push(dir);
  }
};
walk('');

const stale = [...EXCLUDED.keys()].filter((d) => !trackedDirs.has(d) || isFull(d));

// The list above only proves the config is well-formed. It was well-formed for
// weeks while no worktree ever had it applied, so every worktree carried the
// 1.7 GB data/raw copy the list exists to exclude. Check the effect, not the
// spelling — and check the real effect, not a proxy for it. `core.sparseCheckout`
// only says cone mode is nominally on; it says nothing about which patterns are
// in effect. A worktree toggled on after a full checkout, or created under an
// older sparsePaths list from before a directory was excluded, reads `true` and
// would pass a boolean-only check while still carrying the excluded directory in
// full. So check for the directory's presence on disk, which is the outcome we
// actually care about, alongside the boolean.
const WORKTREE_ROOT = '.claude/worktrees';
const unapplied = [];
let worktreeDirs = [];
try {
  worktreeDirs = readdirSync(resolve(ROOT, WORKTREE_ROOT), { withFileTypes: true })
    .filter((entry) => entry.isDirectory())
    .map((entry) => entry.name);
} catch (error) {
  // No worktrees on this checkout is normal; anything else (e.g. a permissions
  // failure) is a real problem and must not silently read as "nothing to verify".
  if (error.code !== 'ENOENT') throw error;
}

for (const name of worktreeDirs) {
  const worktree = resolve(ROOT, WORKTREE_ROOT, name);
  let enabled = '';
  try {
    enabled = execFileSync('git', ['-C', worktree, 'config', 'core.sparseCheckout'], {
      encoding: 'utf8',
      stdio: ['ignore', 'pipe', 'ignore'],
    }).trim();
  } catch {
    enabled = '';
  }
  const leaked = [...EXCLUDED.keys()].filter((dir) => existsSync(resolve(worktree, dir)));
  if (enabled !== 'true' || leaked.length) {
    unapplied.push({ name, enabled: enabled === 'true', leaked });
  }
}

if (unapplied.length) {
  console.error(
    '\nWorktrees with worktree.sparsePaths not actually applied. core.sparseCheckout=true\n' +
      'says cone mode is nominally on; it does not say the exclusion took effect, so each\n' +
      'entry below names which is wrong:\n' +
      unapplied
        .map(({ name, enabled, leaked }) => {
          const reasons = [];
          if (!enabled) reasons.push('core.sparseCheckout is not enabled');
          if (leaked.length) reasons.push(`still contains ${leaked.join(', ')}`);
          return `  ${WORKTREE_ROOT}/${name}: ${reasons.join('; ')}`;
        })
        .join('\n') +
      '\n\nRetrofit one with:\n' +
      `  git -C <worktree> sparse-checkout set --cone -- <the directories in ${SETTINGS} worktree.sparsePaths>`,
  );
}

if (malformed.length) {
  console.error(
    `${SETTINGS}: worktree.sparsePaths entries git sparse-checkout --cone will reject.\n` +
      'Cone mode takes bare directory names ("data/pricing-cache"), never gitignore\n' +
      'patterns — one bad entry aborts every worktree creation:\n' +
      malformed.map((p) => `  ${JSON.stringify(p)}`).join('\n'),
  );
}
if (unknown.length) {
  console.error(
    `\n${SETTINGS}: worktree.sparsePaths names directories that are not tracked at HEAD.\n` +
      'Drop them, or fix the spelling if one was renamed:\n' +
      unknown.map((p) => `  ${p}`).join('\n'),
  );
}
if (omitted.length) {
  console.error(
    `\nTracked directories missing from Claude worktrees. Add each to worktree.sparsePaths\n` +
      `in ${SETTINGS}, or to EXCLUDED in this script with the reason it stays out:\n` +
      omitted.map((d) => `  ${d}`).join('\n'),
  );
}
if (stale.length) {
  console.error(
    '\nEXCLUDED entries in this script that no longer exclude anything — the directory\n' +
      'is gone from HEAD, or sparsePaths now covers it. Remove them:\n' +
      stale.map((d) => `  ${d}`).join('\n'),
  );
}
if (malformed.length || unknown.length || omitted.length || stale.length || unapplied.length)
  process.exit(1);

console.log(
  `worktree sparsePaths ok — ${sparsePaths.length} cone-mode entries cover every tracked ` +
    `directory except ${[...EXCLUDED.keys()].join(', ')}`,
);
