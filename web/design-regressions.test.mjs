import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';

const css = readFileSync(new URL('../index.html', import.meta.url), 'utf8');

test('mobile campground CTAs stay on one flexible row', () => {
  assert.match(css, /\.cg-actions \{ display: flex; flex-direction: row; flex-wrap: nowrap; gap: 8px; \}/);
  assert.match(css, /\.cg-actions > \.cg-btn \{ flex: 1 1 0; min-width: 0; \}/);
  assert.match(css, /\.cg-btn \{[\s\S]*min-width: 0;[\s\S]*text-overflow: ellipsis;[\s\S]*white-space: nowrap;/);
});

test('availability matrix pressed booking states keep the brand color family', () => {
  assert.match(css, /\.cg-site-matrix-cell-button\.is-armed:active \{\s*background: var\(--rt-brand-press\);\s*\}/);
  assert.match(css, /\.cg-btn-primary:active \{ background: var\(--rt-brand-press\); \}/);
  assert.match(css, /\.cg-sites-row-link:active \.cg-sites-row-book \{ background: var\(--rt-brand-press\); \}/);
});
