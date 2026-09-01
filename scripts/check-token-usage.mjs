#!/usr/bin/env node
/**
 * Design-token reference guardrail.
 *
 * Two rules, both about references that fail SILENTLY:
 *
 *   1. Every `var(--rt-*)` names a token `tokens.css` actually defines.
 *      `var(--rt-does-not-exist)` with no fallback resolves to nothing — an
 *      invisible button, dark inherited text — which no unit test can see,
 *      because jsdom does no layout.
 *   2. Every declaration containing `var(--rt-*)` has balanced parentheses.
 *      An unbalanced one is dropped whole by the browser, so the property
 *      silently reverts.
 *
 * Was `web/design-system/tokens-usage.test.mjs`, the last `node:test` suite in the
 * repo, walking the vanilla tree. It moved here rather than into Vitest for two
 * reasons: it is a filesystem-walking repo guardrail exactly like its two siblings
 * (`check-color-tokens.mjs`, `check-css-blocks.mjs`) rather than a unit test of a
 * module, and putting it here is what let the `node --test` step come out of CI
 * entirely.
 *
 * It also scans MORE than it used to. The old suite walked `web/` only, so the React
 * tree's stylesheets were never covered by it; this walks `frontend/src`, and `.ts`
 * and `.tsx` alongside `.css` because a component can put `var(--rt-*)` in an inline
 * style or a MapLibre paint expression.
 *
 *   node scripts/check-token-usage.mjs
 */

import { readFileSync, readdirSync, statSync } from 'node:fs';
import { join, relative, resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const TOKENS_CSS = 'frontend/src/tokens/tokens.css';
// roadtrip-zion.css and its bridge declare their own --rt-* roles — the LDS
// theme direction's own vocabulary (--rt-ink, --rt-action-container, etc.),
// consumed only within these two files — that tokens.css never lists and
// never should; see roadtrip-zion-bridge.css's own header.
const THEME_TOKEN_SOURCES = [
  'frontend/src/tokens/roadtrip-zion.css',
  'frontend/src/tokens/roadtrip-zion-bridge.css',
];
const ROOTS = ['frontend/src'];
const EXTENSIONS = ['.css', '.ts', '.tsx'];

/**
 * Custom properties declared and consumed inside a single component. They are
 * deliberately not global tokens, so they are allowlisted by name — see the comment
 * on rule 1 for why "has a fallback" is not a safe exemption.
 *
 * The old suite's copy of this list was EMPTY, and only because it never walked the
 * React tree: widening the scan to `frontend/src` surfaced all five below at once.
 * Each was checked to be declared or assigned, not misspelled — which is the whole
 * point of the rule, since three of them are read behind a fallback and a typo would
 * have rendered as the fallback forever.
 */
const COMPONENT_LOCAL = new Set([
  // Declared in availability.css and re-set by the Site column's drag-resize.
  '--rt-site-column-width',
  // Declared in availability.css; WatchPopover positions against the same number.
  '--rt-watch-editor-width',
  // Declared in cell-book-popover.css; CellBookPopover positions against it, for
  // the same reason the watch editor does.
  '--rt-cell-book-pop-width',
  '--rt-watch-editor-mobile-margin',
  // Set from React inline styles in SiteMatrix.tsx (lines ~352 and ~370), never
  // declared in CSS, and read with a fallback for the pre-measurement first paint.
  '--rt-site-dates-width',
  '--rt-site-matrix-viewport-width',
  // Layout plumbing, not design tokens: geometry one fixed surface has to hand to
  // another because CSS cannot read a sibling's box. See docs/frontend-components.md.
  // Declared 0 in shell.css, set by sandbox.css while the banner is up, and read by
  // map.css, legend.css, topbar.css, drawer.css and the body padding.
  '--rt-chrome-top',
  // Declared in sandbox.css; pins the banner's height and the room it claims.
  '--rt-sandbox-banner-h',
  // Published from JS by features/trip/useTopbarClearance.ts, read by drawer.css.
  '--rt-topbar-bottom',
  // Declared and consumed entirely inside drawer.css.
  '--rt-drawer-clearance',
  '--rt-drawer-clearance-gap',
  // The POI page's own rhythm: declared in domain/poi/poi-page.css, which is the
  // one stylesheet for both the drawer panel and the routed page. drawer.css reads
  // the gutter so its loading and error states line up with the page that replaces
  // them. Geometry the page owns, not colour or a scale step.
  '--rt-poi-gutter',
  '--rt-poi-block-gap',
  '--rt-poi-hero-height',
  '--rt-poi-measure',
  '--rt-poi-card-width',
  // Declared and consumed entirely inside the nights table's own rules in
  // poi-page.css — column geometry, not colour or a scale step.
  '--rt-poi-avail-night-col',
  '--rt-poi-avail-place-col',
  // Declared and consumed entirely inside shell.css — page padding, nothing hands
  // it anywhere. Listed here only because it is not a design token.
  '--rt-shell-pad',
  // Declared and consumed entirely inside legend.css — clears the account pill
  // above it, not a design token.
  '--rt-acct-clearance',
  // Published from JS by features/account/useAcctClearance.ts, read by topbar.css.
  '--rt-acct-left',
]);

const defined = new Set(
  [TOKENS_CSS, ...THEME_TOKEN_SOURCES].flatMap((f) =>
    [...readFileSync(join(ROOT, f), 'utf8').matchAll(/(--rt-[a-z0-9-]+)\s*:/g)].map((m) => m[1]),
  ),
);

function* walk(dir) {
  for (const entry of readdirSync(join(ROOT, dir), { withFileTypes: true })) {
    if (entry.name === 'node_modules' || entry.name === 'dist') continue;
    const path = join(dir, entry.name);
    if (entry.isDirectory()) yield* walk(path);
    else if (EXTENSIONS.some((e) => path.endsWith(e))) yield path;
  }
}

const files = ROOTS.flatMap((root) => [...walk(root)]);

// Rule 1. Every reference is checked, with or without a fallback. A fallback makes a
// *missing* token survivable, but it makes a *misspelled* one invisible:
// `var(--rt-accent, #1a73e8)` silently renders a hardcoded light-theme blue in a dark
// app, forever. That is the bug this guards against.
const missing = [];
for (const file of files) {
  if (file === TOKENS_CSS) continue;
  const source = readFileSync(join(ROOT, file), 'utf8');
  for (const m of source.matchAll(/var\(\s*(--rt-[a-z0-9-]+)/g)) {
    const token = m[1];
    if (!defined.has(token) && !COMPONENT_LOCAL.has(token)) missing.push(`${file} → ${token}`);
  }
}

// Rule 2. A stripped fallback is the easy edit to get wrong. Removing the tail of
// `var(--rt-border-strong, rgba(255,255,255,0.13))` with a naive
// `var\((--rt-[a-z-]+), [^)]*\)` stops at the rgba's OWN closing paren and leaves
// `var(--rt-border-strong))` behind. Browsers drop the whole declaration, so a border
// silently disappears — invisible to unit tests, and invisible in review because the
// token name still looks right. Counting parens per declaration catches it without
// banning legitimate nesting like min(var(--a), calc(100vw - var(--b))).
const broken = [];
for (const file of files) {
  const source = readFileSync(join(ROOT, file), 'utf8');
  // Scanned over the whole source rather than line by line: a wrapped declaration
  // like drawer.css's `padding-top: min(\n  calc(...),\n  ...\n)` puts the property
  // and its parens on different lines, and a per-line scan matches neither half —
  // so the declaration most in need of the check was the one exempt from it.
  {
    // Only the value side of a real `prop: value` declaration. The property must sit
    // at a declaration boundary (line start, `;` or `{`), which is what keeps a TS
    // ternary's `cond ? a : 'var(--x)'` out of the scan.
    for (const m of source.matchAll(/(?:^|[;{])\s*(?:--)?[a-z][a-z-]*\s*:([^;{}]*)/gm)) {
      const value = m[1];
      if (!value.includes('var(--rt-')) continue;
      const open = (value.match(/\(/g) || []).length;
      const close = (value.match(/\)/g) || []).length;
      const line = source.slice(0, m.index).split('\n').length;
      if (open !== close) broken.push(`${file}:${line}: ${value.trim().split('\n')[0]}`);
    }
  }
}

if (missing.length) {
  console.error(
    `Undefined design tokens (add to ${TOKENS_CSS}, use an existing one, or — for a\n` +
      `component-local property — add it to COMPONENT_LOCAL in this script):\n` +
      missing.map((m) => `  ${m}`).join('\n'),
  );
}
if (broken.length) {
  console.error(
    '\nUnbalanced parentheses in a declaration using var(--rt-*). The declaration\n' +
      'is dropped by the browser, so the property silently reverts:\n' +
      broken.map((b) => `  ${b}`).join('\n'),
  );
}
if (missing.length || broken.length) process.exit(1);

console.log(
  `token usage ok — ${files.length} files scanned against ${defined.size} tokens ` +
    `in ${relative('.', TOKENS_CSS)}, every var(--rt-*) defined and balanced`,
);
