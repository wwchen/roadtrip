// Guards against referencing a `--rt-*` custom property that isn't defined in
// tokens.css. `var(--rt-does-not-exist)` with no fallback silently resolves to
// nothing — an invisible button, dark inherited text — which template-only
// tests can't see. This catches it at the CSS layer.

import assert from 'node:assert/strict';
import test from 'node:test';
import { readFileSync, readdirSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const dsDir = path.dirname(fileURLToPath(import.meta.url)); // web/design-system
const webDir = path.resolve(dsDir, '..');                   // web/

const tokensCss = readFileSync(path.join(dsDir, 'tokens.css'), 'utf8');
const defined = new Set(
  [...tokensCss.matchAll(/(--rt-[a-z0-9-]+)\s*:/g)].map((m) => m[1]),
);

// Custom properties declared and consumed inside a single component. They are
// deliberately not global tokens, so they're allowlisted by name — see the
// comment on the rule below for why "has a fallback" is not a safe exemption.
const COMPONENT_LOCAL = new Set([
  '--rt-modal-width',
  '--rt-watch-editor-width',
  '--rt-watch-editor-mobile-margin',
]);

// .js is walked alongside .css on purpose: several components (topbar.js,
// topbar/auth.js, topbar/alerts.js, availability/watch-editor.js) inject their
// CSS as a template literal into a <style> tag, which a .css-only scan misses.
function* walkFiles(dir) {
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    if (entry.name === 'node_modules') continue;
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) yield* walkFiles(full);
    else if (/\.(css|js)$/.test(entry.name)) yield full;
  }
}

test('every var(--rt-*) reference is a token defined in tokens.css', () => {
  // Every reference is checked, with or without a fallback. A fallback makes a
  // *missing* token survivable, but it makes a *misspelled* one invisible:
  // `var(--rt-accent, #1a73e8)` silently renders a hardcoded light-theme blue
  // in a dark app, forever. That is the bug this guards against.
  const missing = [];
  for (const file of walkFiles(webDir)) {
    if (path.basename(file) === 'tokens.css') continue;
    const source = readFileSync(file, 'utf8');
    for (const m of source.matchAll(/var\(\s*(--rt-[a-z0-9-]+)/g)) {
      const token = m[1];
      if (!defined.has(token) && !COMPONENT_LOCAL.has(token)) {
        missing.push(`${path.relative(webDir, file)} → ${token}`);
      }
    }
  }
  assert.deepEqual(
    missing,
    [],
    `Undefined design tokens (add to tokens.css, use an existing one, or — for a\ncomponent-local property — add it to COMPONENT_LOCAL above):\n${missing.join('\n')}`,
  );
});

test('every declaration containing var(--rt-*) has balanced parentheses', () => {
  // A stripped fallback is the easy edit to get wrong. Removing the tail of
  // `var(--rt-border-strong, rgba(255,255,255,0.13))` with a naive
  // `var\((--rt-[a-z-]+), [^)]*\)` stops at the rgba's OWN closing paren and
  // leaves `var(--rt-border-strong))` behind. Browsers drop the whole
  // declaration, so a border silently disappears — invisible to template
  // tests, and invisible in review because the token name still looks right.
  // Counting parens per declaration catches it without banning legitimate
  // nesting like min(var(--a), calc(100vw - var(--b))).
  const broken = [];
  for (const file of walkFiles(webDir)) {
    const source = readFileSync(file, 'utf8');
    source.split('\n').forEach((line, i) => {
      if (!line.includes('var(--rt-')) return;
      // Only the value side of a real `prop: value` declaration. The property
      // must sit at a declaration boundary (line start, `;` or `{`), which is
      // what keeps a JS ternary's `cond ? a : 'var(--x)'` out of the scan.
      for (const m of line.matchAll(/(?:^|[;{])\s*(?:--)?[a-z][a-z-]*\s*:([^;{}]*)/g)) {
        const value = m[1];
        if (!value.includes('var(--rt-')) continue;
        const open = (value.match(/\(/g) || []).length;
        const close = (value.match(/\)/g) || []).length;
        if (open !== close) {
          broken.push(`${path.relative(webDir, file)}:${i + 1}: ${line.trim()}`);
        }
      }
    });
  }
  assert.deepEqual(
    broken,
    [],
    `Unbalanced parentheses in a declaration using var(--rt-*). The declaration\nis dropped by the browser, so the property silently reverts:\n${broken.join('\n')}`,
  );
});
