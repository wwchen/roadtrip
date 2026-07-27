import assert from 'node:assert/strict';
import test from 'node:test';

// ── Minimal globalThis.document stub ─────────────────────────────────────────
//
// auth.js uses:
//   document.getElementById(ROOT_ID)   → returns our fake rootEl
//   document.getElementById(STYLE_ID)  → returns non-null so injectAuthStyles
//                                         early-returns and never touches head
//   document.createElement('style')    → never reached (STYLE_ID found)
//   document.head.appendChild(...)     → never reached
//
// We capture the 'click' listener via rootEl.addEventListener so we can fire
// it synthetically without a real DOM.

let capturedClickHandler = null;

function makeRootEl() {
  return {
    innerHTML: '',
    hidden: false,
    addEventListener(event, handler) {
      if (event === 'click') capturedClickHandler = handler;
    },
  };
}

function makeStubDocument(rootEl) {
  return {
    getElementById(id) {
      if (id === 'tb-auth')        return rootEl;
      if (id === 'tb-auth-styles') return {}; // truthy → injectAuthStyles no-ops
      return null;
    },
    createElement() { return { id: '', textContent: '' }; },
    head: { appendChild() {} },
  };
}

// ── Helper: fire a synthetic click for a given action ─────────────────────────
function fireAction(action) {
  assert.ok(capturedClickHandler, 'click handler must have been registered');
  capturedClickHandler({
    preventDefault() {},
    target: {
      closest: () => ({ dataset: { authAction: action } }),
    },
  });
}

// ── Helper: set up a fresh module environment per test ────────────────────────
async function setup(meObject) {
  capturedClickHandler = null; // Reset shared test state
  const rootEl = makeRootEl();

  const originalDocument = globalThis.document;
  globalThis.document = makeStubDocument(rootEl);

  const mountLoginCardCalls = [];
  const mountSettingsModalCalls = [];
  const signOutCalls = [];

  const { initAuth, refresh } = await import('./auth.js');

  let resolveFetchMe;
  const fetchMePromise = new Promise((resolve) => { resolveFetchMe = resolve; });

  initAuth({
    _fetchMe: () => fetchMePromise,
    _mountLoginCard:    () => mountLoginCardCalls.push(1),
    _mountSettingsModal: () => mountSettingsModalCalls.push(1),
    _signOut:           () => signOutCalls.push(1),
  });

  // Complete the async refresh
  resolveFetchMe(meObject);
  await fetchMePromise;
  // Give the microtask queue a tick to let render() run
  await new Promise((r) => setTimeout(r, 0));

  function restore() {
    if (originalDocument === undefined) {
      delete globalThis.document;
    } else {
      globalThis.document = originalDocument;
    }
  }

  return { rootEl, mountLoginCardCalls, mountSettingsModalCalls, signOutCalls, restore };
}

// ── Tests ─────────────────────────────────────────────────────────────────────

test('auth_enabled:false → row is hidden and empty after refresh', async () => {
  const { rootEl, restore } = await setup({ auth_enabled: false });
  try {
    assert.equal(rootEl.hidden, true);
    assert.equal(rootEl.innerHTML, '');
  } finally {
    restore();
  }
});

test('anonymous (auth_enabled:true, authenticated:false) → sign-in action calls mountLoginCard', async () => {
  const { rootEl, mountLoginCardCalls, restore } = await setup({
    auth_enabled: true,
    authenticated: false,
  });
  try {
    assert.equal(rootEl.hidden, false);
    assert.match(rootEl.innerHTML, /data-auth-action="sign-in"/);

    fireAction('sign-in');
    assert.equal(mountLoginCardCalls.length, 1, 'mountLoginCard should have been called once');
  } finally {
    restore();
  }
});

test('authenticated → markup contains open-settings action', async () => {
  const { rootEl, restore } = await setup({
    auth_enabled: true,
    authenticated: true,
    user: { display_name: 'Alice', email: 'alice@example.com' },
  });
  try {
    assert.equal(rootEl.hidden, false);
    assert.match(rootEl.innerHTML, /data-auth-action="open-settings"/);
    assert.match(rootEl.innerHTML, /data-auth-action="sign-out"/);
  } finally {
    restore();
  }
});

test('authenticated → open-settings action calls mountSettingsModal', async () => {
  const { mountSettingsModalCalls, restore } = await setup({
    auth_enabled: true,
    authenticated: true,
    user: { display_name: 'Alice', email: 'alice@example.com' },
  });
  try {
    fireAction('open-settings');
    assert.equal(mountSettingsModalCalls.length, 1, 'mountSettingsModal should have been called once');
  } finally {
    restore();
  }
});

test('authenticated → sign-out action calls signOut (not the modal mounts)', async () => {
  const { mountLoginCardCalls, mountSettingsModalCalls, signOutCalls, restore } = await setup({
    auth_enabled: true,
    authenticated: true,
    user: { display_name: 'Alice', email: 'alice@example.com' },
  });
  try {
    fireAction('sign-out');
    assert.equal(signOutCalls.length, 1, 'injected signOut should have been called once');
    assert.equal(mountLoginCardCalls.length, 0);
    assert.equal(mountSettingsModalCalls.length, 0);
  } finally {
    restore();
  }
});

test('failed /api/me → row is hidden and empty (auth optional degrades gracefully)', async () => {
  capturedClickHandler = null; // Reset shared test state
  const rootEl = makeRootEl();

  const originalDocument = globalThis.document;
  globalThis.document = makeStubDocument(rootEl);

  const { initAuth, refresh } = await import('./auth.js');

  let rejectFetchMe;
  const fetchMePromise = new Promise((resolve, reject) => { rejectFetchMe = reject; });

  initAuth({
    _fetchMe: () => fetchMePromise,
    _mountLoginCard:    () => {},
    _mountSettingsModal: () => {},
    _signOut:           () => {},
  });

  // Reject the async refresh
  rejectFetchMe(new Error('Network error'));
  try {
    await fetchMePromise;
  } catch {
    // Expected rejection; continue
  }
  // Give the microtask queue a tick to let render(null) run
  await new Promise((r) => setTimeout(r, 0));

  try {
    assert.equal(rootEl.hidden, true, 'row should be hidden when fetchMe rejects');
    assert.equal(rootEl.innerHTML, '', 'row should be empty when fetchMe rejects');
  } finally {
    if (originalDocument === undefined) {
      delete globalThis.document;
    } else {
      globalThis.document = originalDocument;
    }
  }
});
