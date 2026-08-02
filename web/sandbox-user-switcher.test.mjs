import assert from 'node:assert/strict';
import test from 'node:test';

import { renderUserSwitcher, initUserSwitcher } from './sandbox-user-switcher.js';

// ── Minimal document stub ─────────────────────────────────────────────────────
// renderUserSwitcher uses:
//   doc.createElement(tag) → returns element-like object
//   doc.body.append(child)  → tracks appended children
//   doc.cookie = '...'      → sets cookie string
// We don't need a real DOM; a plain object is enough.

function makeEl() {
  return {
    attrs: {},
    children: [],
    _text: '',
    handler: null,
    setAttribute(k, v) { this.attrs[k] = v; },
    addEventListener(_e, h) { this.handler = h; },
    append(c) { this.children.push(c); },
    get textContent() { return this._text; },
    set textContent(v) { this._text = v; },
  };
}

function fakeDoc() {
  const body = makeEl();
  return {
    cookie: '',
    createElement() { return makeEl(); },
    body,
  };
}

// ── renderUserSwitcher ────────────────────────────────────────────────────────

test('renders nothing when auth_enabled is true', () => {
  const doc = fakeDoc();
  const el = renderUserSwitcher(
    [{ id: 1, name: 'Will', roles: ['admin'] }],
    { auth_enabled: true },
    doc,
    { reload() {} },
  );
  assert.equal(el, null);
  assert.equal(doc.body.children.length, 0);
});

test('renders nothing when currentMe is null', () => {
  const doc = fakeDoc();
  const el = renderUserSwitcher([], null, doc, { reload() {} });
  assert.equal(el, null);
});

test('renders nothing when users list is empty', () => {
  const doc = fakeDoc();
  const el = renderUserSwitcher([], { auth_enabled: false }, doc, { reload() {} });
  assert.equal(el, null);
  assert.equal(doc.body.children.length, 0);
});

test('renders nothing when users is not an array', () => {
  const doc = fakeDoc();
  const el = renderUserSwitcher(null, { auth_enabled: false }, doc, { reload() {} });
  assert.equal(el, null);
});

test('lists seeded users when auth_enabled is false', () => {
  const doc = fakeDoc();
  const el = renderUserSwitcher(
    [{ id: 1, name: 'Will', roles: ['admin'] }, { id: 2, name: 'Matt', roles: [] }],
    { auth_enabled: false },
    doc,
    { reload() {} },
  );
  assert.ok(el, 'should return a wrapper element');
  assert.equal(el.children.length, 2, 'should render one button per user');
  assert.equal(doc.body.children.length, 1, 'should append switcher to body');
  assert.equal(doc.body.children[0], el);
});

test('admin user button text includes (admin) suffix', () => {
  const doc = fakeDoc();
  const el = renderUserSwitcher(
    [{ id: 1, name: 'Alice', roles: ['admin'] }, { id: 2, name: 'Bob', roles: [] }],
    { auth_enabled: false },
    doc,
    { reload() {} },
  );
  assert.ok(el);
  const adminBtn = el.children.find(b => b._text.includes('(admin)'));
  const plainBtn = el.children.find(b => !b._text.includes('(admin)'));
  assert.ok(adminBtn, 'admin user should have (admin) label');
  assert.ok(plainBtn, 'non-admin user should not have (admin) label');
  assert.equal(plainBtn._text, 'Bob');
});

test('selecting a user sets the rt_session=sandbox:<id> cookie and reloads', () => {
  const doc = fakeDoc();
  let reloaded = false;
  const el = renderUserSwitcher(
    [{ id: 2, name: 'Matt', roles: [] }],
    { auth_enabled: false },
    doc,
    { reload() { reloaded = true; } },
  );
  assert.ok(el);
  el.children[0].handler();
  assert.match(doc.cookie, /rt_session=sandbox:2/, 'cookie should use rt_session name');
  assert.match(doc.cookie, /path=\//, 'cookie should include path=/');
  assert.equal(reloaded, true, 'loc.reload() should be called');
});

test('clicking different users sets the correct id in cookie', () => {
  const doc = fakeDoc();
  const cookies = [];
  const loc = { reload() { cookies.push(doc.cookie); doc.cookie = ''; } };
  const el = renderUserSwitcher(
    [{ id: 10, name: 'User10', roles: [] }, { id: 99, name: 'User99', roles: ['admin'] }],
    { auth_enabled: false },
    doc,
    loc,
  );
  el.children[0].handler();
  el.children[1].handler();
  assert.match(cookies[0], /sandbox:10/);
  assert.match(cookies[1], /sandbox:99/);
});

// ── initUserSwitcher ──────────────────────────────────────────────────────────

test('initUserSwitcher renders when auth off and users present', async () => {
  const doc = fakeDoc();
  const fetchFn = async (url) => {
    if (url === '/api/me') return { ok: true, json: async () => ({ auth_enabled: false }) };
    if (url === '/api/sandbox/users') return { ok: true, json: async () => [{ id: 1, name: 'Alice', roles: [] }] };
    throw new Error(`unexpected url: ${url}`);
  };
  await initUserSwitcher(doc, fetchFn);
  assert.equal(doc.body.children.length, 1, 'should render switcher');
});

test('initUserSwitcher does not render when auth is enabled', async () => {
  const doc = fakeDoc();
  const fetchFn = async (url) => {
    if (url === '/api/me') return { ok: true, json: async () => ({ auth_enabled: true }) };
    if (url === '/api/sandbox/users') return { ok: true, json: async () => [{ id: 1, name: 'Alice', roles: [] }] };
    throw new Error(`unexpected url: ${url}`);
  };
  await initUserSwitcher(doc, fetchFn);
  assert.equal(doc.body.children.length, 0, 'should not render when auth enabled');
});

test('initUserSwitcher does not render when sandbox/users returns 404', async () => {
  const doc = fakeDoc();
  const fetchFn = async (url) => {
    if (url === '/api/me') return { ok: true, json: async () => ({ auth_enabled: false }) };
    if (url === '/api/sandbox/users') return { ok: false, status: 404 };
    throw new Error(`unexpected url: ${url}`);
  };
  await initUserSwitcher(doc, fetchFn);
  assert.equal(doc.body.children.length, 0, 'should not render when users endpoint 404s');
});

test('initUserSwitcher does not render when fetch throws', async () => {
  const doc = fakeDoc();
  const fetchFn = async () => { throw new Error('network error'); };
  await initUserSwitcher(doc, fetchFn);
  assert.equal(doc.body.children.length, 0, 'should not throw or render on fetch error');
});

test('initUserSwitcher does not render when /api/me is not ok', async () => {
  const doc = fakeDoc();
  const fetchFn = async (url) => {
    if (url === '/api/me') return { ok: false };
    if (url === '/api/sandbox/users') return { ok: true, json: async () => [{ id: 1, name: 'Alice', roles: [] }] };
    throw new Error(`unexpected url: ${url}`);
  };
  await initUserSwitcher(doc, fetchFn);
  assert.equal(doc.body.children.length, 0, 'should not render when /api/me not ok');
});
