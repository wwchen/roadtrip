import assert from 'node:assert/strict';
import test from 'node:test';

import { renderSandboxBanner, initSandboxBanner } from './sandbox-banner.js';

// ── Minimal document stub ─────────────────────────────────────────────────────
// renderSandboxBanner uses:
//   doc.createElement(tag) → returns element-like object with setAttribute /
//                            textContent / append / children
//   doc.body.append(child)  → tracks appended children
// We don't need a real DOM; a plain object is enough.

function makeEl() {
  return {
    attrs: {},
    children: [],
    _text: '',
    setAttribute(k, v) { this.attrs[k] = v; },
    append(c) { this.children.push(c); },
    get textContent() { return this._text; },
    set textContent(v) { this._text = v; },
  };
}

function fakeDoc() {
  const body = makeEl();
  return {
    createElement() { return makeEl(); },
    body,
  };
}

// ── renderSandboxBanner ───────────────────────────────────────────────────────

test('renders banner for sandbox env', () => {
  const doc = fakeDoc();
  const banner = renderSandboxBanner({ env: 'sandbox', sha: 'abc1234', branch: 'fix-foo' }, doc);
  assert.ok(banner, 'should return a banner element');
  assert.equal(doc.body.children.length, 1, 'should append banner to body');
  assert.equal(doc.body.children[0], banner, 'appended element should be the returned banner');
  assert.equal(banner.attrs.role, 'status');
});

test('renders nothing for prod env', () => {
  const doc = fakeDoc();
  const banner = renderSandboxBanner({ env: 'prod', sha: 'x', branch: 'master' }, doc);
  assert.equal(banner, null, 'should return null for prod');
  assert.equal(doc.body.children.length, 0, 'should not append to body for prod');
});

test('renders nothing for local env', () => {
  const doc = fakeDoc();
  const banner = renderSandboxBanner({ env: 'local', sha: 'x', branch: 'main' }, doc);
  assert.equal(banner, null, 'should return null for local');
  assert.equal(doc.body.children.length, 0);
});

test('renders nothing when buildInfo is null', () => {
  const doc = fakeDoc();
  const banner = renderSandboxBanner(null, doc);
  assert.equal(banner, null, 'should return null when buildInfo is null');
});

test('banner sha element has correct text for sandbox', () => {
  const doc = fakeDoc();
  const banner = renderSandboxBanner({ env: 'sandbox', sha: 'deadbeef', branch: 'feature-branch' }, doc);
  assert.ok(banner);
  const shaEl = banner.children.find(c => c._text === 'deadbeef');
  assert.ok(shaEl, 'should have child element with sha text');
  const branchEl = banner.children.find(c => c._text === 'feature-branch');
  assert.ok(branchEl, 'should have child element with branch text');
});

// ── initSandboxBanner ─────────────────────────────────────────────────────────

test('initSandboxBanner renders when fetch returns sandbox env', async () => {
  const doc = fakeDoc();
  const fetchFn = async () => ({
    ok: true,
    json: async () => ({ env: 'sandbox', sha: 'abc123', branch: 'main' }),
  });
  await initSandboxBanner(doc, fetchFn);
  assert.equal(doc.body.children.length, 1, 'should render banner for sandbox');
});

test('initSandboxBanner does not render when fetch returns prod env', async () => {
  const doc = fakeDoc();
  const fetchFn = async () => ({
    ok: true,
    json: async () => ({ env: 'prod', sha: 'x', branch: 'master' }),
  });
  await initSandboxBanner(doc, fetchFn);
  assert.equal(doc.body.children.length, 0, 'should not render banner for prod');
});

test('initSandboxBanner does not render when fetch throws', async () => {
  const doc = fakeDoc();
  const fetchFn = async () => { throw new Error('network error'); };
  await initSandboxBanner(doc, fetchFn);
  assert.equal(doc.body.children.length, 0, 'should not render banner on fetch error');
});

test('initSandboxBanner does not render when response is not ok', async () => {
  const doc = fakeDoc();
  const fetchFn = async () => ({ ok: false });
  await initSandboxBanner(doc, fetchFn);
  assert.equal(doc.body.children.length, 0, 'should not render banner when response is not ok');
});
