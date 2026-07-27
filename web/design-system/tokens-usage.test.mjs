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
const accountDir = path.resolve(dsDir, '..', 'account');

const tokensCss = readFileSync(path.join(dsDir, 'tokens.css'), 'utf8');
const defined = new Set(
  [...tokensCss.matchAll(/(--rt-[a-z0-9-]+)\s*:/g)].map((m) => m[1]),
);

const targets = [
  ...readdirSync(dsDir)
    .filter((f) => f.endsWith('.css') && f !== 'tokens.css')
    .map((f) => path.join(dsDir, f)),
  ...readdirSync(accountDir)
    .filter((f) => f.endsWith('.css'))
    .map((f) => path.join(accountDir, f)),
];

test('every fallback-less var(--rt-*) reference is a token defined in tokens.css', () => {
  // A `var(--x)` with NO fallback resolves to nothing when --x is undefined —
  // that's the bug (invisible buttons, dark text). A `var(--x, fallback)` is
  // safe: it renders the fallback. So flag only fallback-less references to
  // tokens that aren't defined. (Component-local props like --rt-modal-width
  // are always written with a fallback and are legitimately not in tokens.css.)
  const missing = [];
  for (const file of targets) {
    const css = readFileSync(file, 'utf8');
    for (const m of css.matchAll(/var\(\s*(--rt-[a-z0-9-]+)\s*([,)])/g)) {
      const token = m[1];
      const hasFallback = m[2] === ',';
      if (!hasFallback && !defined.has(token)) {
        missing.push(`${path.basename(file)} → ${token}`);
      }
    }
  }
  assert.deepEqual(
    missing,
    [],
    `Undefined design tokens with no fallback (add to tokens.css or use an existing one):\n${missing.join('\n')}`,
  );
});
