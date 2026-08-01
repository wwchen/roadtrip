import assert from 'node:assert/strict';
import test from 'node:test';
import { loginCardTemplate } from './login-card-template.js';
import { makeFakeEmbeddedAuth } from './embedded-auth-port.js';

test('template renders the sign-in title', () => {
  assert.match(loginCardTemplate({}), /Sign in to Roadtrip/);
});

test('template renders email and password inputs', () => {
  const html = loginCardTemplate({});
  assert.match(html, /data-field="email"/);
  assert.match(html, /type="email"/);
  assert.match(html, /data-field="password"/);
  assert.match(html, /type="password"/);
});

test('template renders a password submit button', () => {
  assert.match(loginCardTemplate({}), /data-action="password-submit"/);
});

test('template renders an error region', () => {
  assert.match(loginCardTemplate({}), /data-role="form-error"/);
});

test('template renders a Google button', () => {
  const html = loginCardTemplate({});
  assert.match(html, /data-action="sign-in-google"/);
  assert.match(html, /Continue with Google/);
});

test('template escapes a dangerous googleLabel', () => {
  const html = loginCardTemplate({ googleLabel: '<script>evil</script>' });
  assert.doesNotMatch(html, /<script>evil/);
  assert.match(html, /&lt;script&gt;/);
});

test('template renders a mode toggle for switching to signup', () => {
  const html = loginCardTemplate({});
  assert.match(html, /data-action="toggle-mode"/);
  assert.match(html, /data-role="mode-prompt"/);
});

test('_handlePasswordSubmit: valid credentials call embeddedAuth then completeLogin', async () => {
  const { _handlePasswordSubmit } = await import('./login-card.js');
  const embeddedAuth = makeFakeEmbeddedAuth({ artifact: 'code-xyz', state: 'st-9' });
  const completed = [];
  const completeLogin = async (artifact, state, returnTo) => { completed.push([artifact, state, returnTo]); };
  const errors = [];
  const loading = [];
  const form = {
    querySelector(sel) {
      if (sel === '[data-field="email"]') return { value: 'a@b.com' };
      if (sel === '[data-field="password"]') return { value: 'secret' };
      return null;
    },
  };
  await _handlePasswordSubmit(form, {
    embeddedAuth, completeLogin,
    onError: (m) => errors.push(m), onLoading: (b) => loading.push(b),
    returnTo: '/watches',
  });
  assert.deepEqual(completed, [['code-xyz', 'st-9', '/watches']]);
  assert.equal(errors.filter(Boolean).length, 0);
  assert.deepEqual(loading, [true, false]);
});

test('_handlePasswordSubmit: empty fields report a validation error and skip the network', async () => {
  const { _handlePasswordSubmit } = await import('./login-card.js');
  let called = false;
  const embeddedAuth = { authenticateWithPassword: async () => { called = true; return { artifact: 'x', state: 's' }; } };
  const errors = [];
  const form = {
    querySelector(sel) {
      if (sel === '[data-field="email"]') return { value: '' };
      if (sel === '[data-field="password"]') return { value: '' };
      return null;
    },
  };
  await _handlePasswordSubmit(form, {
    embeddedAuth, completeLogin: async () => {},
    onError: (m) => errors.push(m), onLoading: () => {}, returnTo: '/',
  });
  assert.equal(called, false);
  assert.equal(errors.filter(Boolean).length, 1);
  assert.match(errors.filter(Boolean)[0], /email|password|required/i);
});

test('_handlePasswordSubmit: invalid_credentials maps to an owned message and clears loading', async () => {
  const { _handlePasswordSubmit } = await import('./login-card.js');
  const embeddedAuth = makeFakeEmbeddedAuth({ failWith: 'invalid_credentials' });
  const errors = [];
  const loading = [];
  const form = {
    querySelector(sel) {
      if (sel === '[data-field="email"]') return { value: 'a@b.com' };
      if (sel === '[data-field="password"]') return { value: 'wrong' };
      return null;
    },
  };
  await _handlePasswordSubmit(form, {
    embeddedAuth, completeLogin: async () => {},
    onError: (m) => errors.push(m), onLoading: (b) => loading.push(b), returnTo: '/',
  });
  assert.equal(errors.filter(Boolean).length, 1);
  assert.match(errors.filter(Boolean)[0], /incorrect|invalid|wrong/i);
  assert.deepEqual(loading, [true, false]);
});

test('_handlePasswordSubmit: signup mode calls signupWithPassword then completeLogin', async () => {
  const { _handlePasswordSubmit } = await import('./login-card.js');
  const calls = [];
  const embeddedAuth = {
    authenticateWithPassword: async () => { calls.push('login'); return { artifact: 'x', state: 's' }; },
    signupWithPassword: async () => { calls.push('signup'); return { artifact: 'code-new', state: 'st-new' }; },
  };
  const completed = [];
  const completeLogin = async (artifact, state, returnTo) => { completed.push([artifact, state, returnTo]); };
  const form = {
    querySelector(sel) {
      if (sel === '[data-field="email"]') return { value: 'new@b.com' };
      if (sel === '[data-field="password"]') return { value: 'longenough' };
      return null;
    },
  };
  await _handlePasswordSubmit(form, {
    embeddedAuth, completeLogin,
    onError: () => {}, onLoading: () => {}, returnTo: '/watches', mode: 'signup',
  });
  assert.deepEqual(calls, ['signup']); // signup path only — login not called directly
  assert.deepEqual(completed, [['code-new', 'st-new', '/watches']]);
});

test('_handlePasswordSubmit: signup user_exists maps to an owned message', async () => {
  const { _handlePasswordSubmit } = await import('./login-card.js');
  const embeddedAuth = makeFakeEmbeddedAuth({ signupFailWith: 'user_exists' });
  const errors = [];
  const form = {
    querySelector(sel) {
      if (sel === '[data-field="email"]') return { value: 'taken@b.com' };
      if (sel === '[data-field="password"]') return { value: 'longenough' };
      return null;
    },
  };
  await _handlePasswordSubmit(form, {
    embeddedAuth, completeLogin: async () => {},
    onError: (m) => errors.push(m), onLoading: () => {}, returnTo: '/', mode: 'signup',
  });
  assert.equal(errors.filter(Boolean).length, 1);
  assert.match(errors.filter(Boolean)[0], /already has an account|sign in instead/i);
});
