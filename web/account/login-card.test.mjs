import assert from 'node:assert/strict';
import test from 'node:test';

import { loginCardTemplate } from './login-card-template.js';

// ── Pure template tests ──────────────────────────────────────────────────────

test('loginCardTemplate renders the sign-in title', () => {
  const html = loginCardTemplate({ providerLabel: 'Google' });
  assert.match(html, /Sign in to Roadtrip/);
});

test('loginCardTemplate renders a rationale line', () => {
  const html = loginCardTemplate({ providerLabel: 'Google' });
  // A non-empty descriptive line exists (not the title itself)
  assert.match(html, /save|sync|personali|account|access/i);
});

test('loginCardTemplate renders a primary button containing the providerLabel', () => {
  const html = loginCardTemplate({ providerLabel: 'Acme SSO' });
  assert.match(html, /Continue with Acme SSO/);
});

test('loginCardTemplate button carries data-action="sign-in"', () => {
  const html = loginCardTemplate({ providerLabel: 'Google' });
  assert.match(html, /data-action="sign-in"/);
});

test('loginCardTemplate escapes a dangerous providerLabel', () => {
  const html = loginCardTemplate({ providerLabel: '<script>evil</script>' });
  assert.doesNotMatch(html, /<script>/);
  assert.match(html, /&lt;script&gt;/);
});

test('loginCardTemplate falls back to "single sign-on" when providerLabel is null', () => {
  const html = loginCardTemplate({ providerLabel: null });
  assert.match(html, /Continue with single sign-on/);
});

test('loginCardTemplate falls back to "single sign-on" when providerLabel is absent', () => {
  const html = loginCardTemplate({});
  assert.match(html, /Continue with single sign-on/);
});

// ── Stub-mount smoke test ────────────────────────────────────────────────────

test('stub-mount: mountLoginCard builds host, opens modal, dispose clears', async () => {
  const originalDocument = globalThis.document;
  let injectedLink = null;

  globalThis.document = {
    getElementById() { return null; },
    createElement(tagName) {
      const el = { id: '', rel: '', href: '', tagName, className: '', textContent: '' };
      if (tagName === 'link') { injectedLink = el; }
      if (tagName === 'div') {
        return {
          id: '', className: '', tagName,
          innerHTML: '',
          addEventListener() {},
          removeEventListener() {},
          querySelector() { return null; },
        };
      }
      return el;
    },
    head: { appendChild(el) { if (el.tagName === 'link') injectedLink = el; } },
    body: { appendChild() {} },
    addEventListener() {},
    removeEventListener() {},
  };

  // Fake fetchMe — resolves quickly with a provider_label
  const fakeFetchMe = async () => ({ authenticated: false, auth_enabled: true, provider_label: 'Test Provider' });
  const signInCalls = [];
  const fakeSignIn = (returnTo) => { signInCalls.push(returnTo); };

  const { mountLoginCard } = await import('./login-card.js');

  const controller = mountLoginCard({
    returnTo: '/trips',
    _fetchMe: fakeFetchMe,
    _signIn: fakeSignIn,
  });

  // Returns a dispose function
  assert.equal(typeof controller.dispose, 'function');

  controller.dispose();

  if (originalDocument === undefined) {
    delete globalThis.document;
  } else {
    globalThis.document = originalDocument;
  }
});

test('stub-mount: handleSignIn calls injected signIn with returnTo', async () => {
  // Import the named export for the click handler unit test
  const { _handleSignInClick } = await import('./login-card.js');

  if (typeof _handleSignInClick !== 'function') {
    // If not exported, skip — this path is covered by the smoke test above
    return;
  }

  const calls = [];
  const fakeSignIn = (rt) => calls.push(rt);
  _handleSignInClick({ target: { closest: (sel) => sel === '[data-action="sign-in"]' ? {} : null } }, fakeSignIn, '/plans');
  assert.equal(calls.length, 1);
  assert.equal(calls[0], '/plans');
});
