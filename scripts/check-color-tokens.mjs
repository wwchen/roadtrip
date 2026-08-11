#!/usr/bin/env node
/**
 * Color-token guardrail.
 *
 * `frontend/src/tokens/tokens.css` is the single source of truth for color.
 * A raw hex anywhere else is invisible to theming: it survives every token
 * override and quietly contradicts the system it sits next to. This check
 * fails the build on one.
 *
 * Covers the React `frontend/` tree, which is all of it — including `.ts`/`.tsx`,
 * since until this scanned them a raw hex in a component would have passed silently.
 *
 * Also verifies the JS bridge stays honest: every fallback key in
 * `frontend/src/tokens/tokens.ts` must name a token that tokens.css actually
 * defines, so a renamed token fails loudly here instead of silently
 * resolving to a stale hardcoded value at runtime.
 *
 *   node scripts/check-color-tokens.mjs
 */

import { readFileSync, readdirSync, statSync } from 'node:fs';
import { join, resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const TOKENS_CSS = 'frontend/src/tokens/tokens.css';
const TOKENS_JS = 'frontend/src/tokens/tokens.ts';

/**
 * Directories and files scanned for raw color.
 *
 * One root: `frontend` is the whole of the site. `web`, `index.html` and
 * `availability.html` were all named here and are all gone from the repo. A name
 * here must exist — `walk` stats every root — so deleting a tree means deleting its
 * entry, which is what made this list shrink three times.
 */
const ROOTS = ['frontend'];
const EXTENSIONS = ['.css', '.html', '.js', '.mjs', '.ts', '.tsx'];

/**
 * Never walked. Build output and installed dependencies are not authored source.
 * Both Vite's `dist` and Storybook's `storybook-static` contain bundled third-party
 * palettes whose values would otherwise read as violations.
 */
const IGNORED_DIRS = new Set(['node_modules', 'dist', 'storybook-static']);

/**
 * Test files quote colors in assertions; they describe the source, they do not
 * ship it.
 */
const TEST_SUFFIXES = ['.test.mjs', '.test.js', '.test.ts', '.test.tsx'];

/**
 * Exemptions. Each needs a reason — an entry without one is a TODO wearing a
 * disguise. Paths are repo-relative; a directory prefix exempts its contents.
 */
const EXEMPT = {
  [TOKENS_CSS]: 'the source of truth — the one place raw values belong',
  [TOKENS_JS]: 'boot/jsdom fallbacks, verified against tokens.css below',
  'frontend/src/tokens/roadtrip-zion.css':
    'a full LDS theme direction, exported byte-identical from Claude Design and kept unedited so re-syncing the direction stays a trivial diff. roadtrip-zion-bridge.css repoints our own --rt-* chrome roles onto it without touching this file',
};

/** Line-level escapes for cases a file-level exemption would over-grant. */
const LINE_EXEMPT = [
  // Vendor logo artwork. A Slack mark is Slack's color, not ours to theme.
  /fill="#[0-9a-fA-F]{3,8}"/,
  // HTML numeric character references (&#10003;) are not colors.
  /&#\d+;/,
  // theme-color is parsed by browser chrome before any stylesheet loads.
  /name="theme-color"/,
  // Issue/PR cross-references in comments.
  /(?:PR|issue|#)\s*#\d+/i,
];

const HEX = /#[0-9a-fA-F]{3,8}\b/;

/**
 * Functional color notation that is NOT already composed from a token, i.e.
 * `rgba(255,255,255,.06)` but not `rgba(var(--rt-c-overlay-rgb), .06)`.
 *
 * These are ratcheted rather than banned. Unlike a hex, most of these are
 * overlays at one-off alphas (0.03, 0.05, 0.14, 0.18, 0.28 ...) with no
 * existing role to map onto. Converting them would mean either inventing a
 * token per alpha or rounding onto the nearest one -- a silent visual change,
 * which is the one thing the token migration promised not to do.
 *
 * So: the counts below are a high-water mark. New raw color of this form
 * fails the build; the existing debt can only shrink. Drop a file's number
 * when you tokenize some, and delete the entry when it reaches zero.
 *
 * Phase 5 took this from 51 occurrences to 7 by deleting their files rather than by
 * tokenizing anything. Moving the sandbox chrome into this tree took it to 1: six of
 * that file's seven were `rgba(255,255,255,a)`, which is literally what
 * `--rt-c-overlay-rgb` expands to, so composing from the primitive was a provable
 * no-op rather than a rounding. A stale entry for a deleted file would not FAIL
 * anything (the check is `found > allowed`), it would just overstate the debt.
 */
const RGB_FUNC = /(?:rgba?|hsla?)\(\s*(?!var\(--rt-)/g;
const LEGACY_RAW_COLOR_BUDGET = {
  // The sandbox bar's own translucent ground. The tints ON it compose from
  // `--rt-c-overlay-rgb`; this one is a one-off surface with no role to map onto, and
  // rounding it onto the nearest (`--rt-overlay-chip`, a different value) is the
  // silent visual change the ratchet exists to avoid.
  'frontend/src/app/sandbox/sandbox.css': 1,
};

function walk(path, out = []) {
  const abs = join(ROOT, path);
  if (statSync(abs).isDirectory()) {
    for (const entry of readdirSync(abs)) {
      if (IGNORED_DIRS.has(entry) || entry.startsWith('.')) continue;
      walk(join(path, entry), out);
    }
  } else if (
    EXTENSIONS.some((e) => path.endsWith(e)) &&
    !TEST_SUFFIXES.some((e) => path.endsWith(e))
  ) {
    out.push(path);
  }
  return out;
}

function isExempt(path) {
  return Object.keys(EXEMPT).some((p) => path === p || path.startsWith(`${p}/`));
}

const violations = [];
for (const root of ROOTS) {
  for (const file of walk(root)) {
    if (isExempt(file)) continue;
    const lines = readFileSync(join(ROOT, file), 'utf8').split('\n');
    lines.forEach((line, i) => {
      if (!HEX.test(line)) return;
      if (LINE_EXEMPT.some((re) => re.test(line))) return;
      violations.push(`${file}:${i + 1}: ${line.trim()}`);
    });
  }
}

// Ratchet: raw rgb()/rgba()/hsl() outside tokens.css may not increase.
const budgetBreaches = [];
for (const root of ROOTS) {
  for (const file of walk(root)) {
    if (isExempt(file)) continue;
    const source = readFileSync(join(ROOT, file), 'utf8');
    const found = (source.match(RGB_FUNC) || []).length;
    const allowed = LEGACY_RAW_COLOR_BUDGET[file] ?? 0;
    if (found > allowed) budgetBreaches.push({ file, found, allowed });
  }
}

// The JS bridge must only name tokens that exist.
const css = readFileSync(join(ROOT, TOKENS_CSS), 'utf8');
const defined = new Set([...css.matchAll(/^\s*(--rt-[a-z0-9-]+)\s*:/gm)].map((m) => m[1]));
const js = readFileSync(join(ROOT, TOKENS_JS), 'utf8');
const referenced = new Set([...js.matchAll(/'(--rt-[a-z0-9-]+)'/g)].map((m) => m[1]));
const orphans = [...referenced].filter((t) => !defined.has(t)).sort();

if (violations.length) {
  console.error(
    `Raw color outside ${TOKENS_CSS} (${violations.length}):\n` +
      violations.map((v) => `  ${v}`).join('\n') +
      `\n\nUse a semantic token: var(--rt-*) in CSS/HTML, token('--rt-*') from ` +
      `${TOKENS_JS} in JS. Add the value to ${TOKENS_CSS} if no role fits yet.`,
  );
}
if (orphans.length) {
  console.error(
    `\n${TOKENS_JS} names tokens ${TOKENS_CSS} does not define:\n` +
      orphans.map((t) => `  ${t}`).join('\n'),
  );
}
if (budgetBreaches.length) {
  console.error(
    '\nRaw rgb()/rgba()/hsl() outside ' + TOKENS_CSS + ' increased:\n' +
      budgetBreaches
        .map((b) => `  ${b.file}: ${b.found} (budget ${b.allowed})`)
        .join('\n') +
      '\n\nCompose from a channel primitive instead, e.g.\n' +
      '  rgba(var(--rt-c-overlay-rgb), 0.06)\n' +
      `If you deliberately tokenized some away, lower the number in ${'LEGACY_RAW_COLOR_BUDGET'}.`,
  );
}
if (violations.length || orphans.length || budgetBreaches.length) process.exit(1);

console.log(
  `color tokens ok — ${defined.size} tokens defined, ` +
    `${referenced.size} bridged to JS, no raw hex outside ${TOKENS_CSS}, ` +
    `${Object.values(LEGACY_RAW_COLOR_BUDGET).reduce((a, b) => a + b, 0)} legacy ` +
    'rgb()/rgba() occurrences held at their high-water mark',
);
