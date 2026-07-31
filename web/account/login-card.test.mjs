import assert from 'node:assert/strict';
import test from 'node:test';
import { loginCardTemplate } from './login-card-template.js';

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
