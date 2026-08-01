import assert from 'node:assert/strict';
import test from 'node:test';
import { makeFakeEmbeddedAuth } from './embedded-auth-port.js';

test('fake resolves with the configured artifact and state', async () => {
  const port = makeFakeEmbeddedAuth({ artifact: 'abc123', state: 'st-1' });
  const result = await port.authenticateWithPassword('a@b.com', 'pw');
  assert.equal(result.artifact, 'abc123');
  assert.equal(result.state, 'st-1');
});

test('fake rejects with a coded error when failWith is set', async () => {
  const port = makeFakeEmbeddedAuth({ failWith: 'invalid_credentials' });
  await assert.rejects(
    () => port.authenticateWithPassword('a@b.com', 'wrong'),
    (err) => err.code === 'invalid_credentials',
  );
});

test('fake signupWithPassword resolves with the configured artifact and state', async () => {
  const port = makeFakeEmbeddedAuth({ artifact: 'abc123', state: 'st-1' });
  const result = await port.signupWithPassword('new@b.com', 'pw');
  assert.equal(result.artifact, 'abc123');
  assert.equal(result.state, 'st-1');
});

test('fake signupWithPassword rejects with a coded error when signupFailWith is set', async () => {
  const port = makeFakeEmbeddedAuth({ signupFailWith: 'user_exists' });
  await assert.rejects(
    () => port.signupWithPassword('taken@b.com', 'pw'),
    (err) => err.code === 'user_exists',
  );
});

test('fake signup and login failures are independent', async () => {
  // signupFailWith must not affect the login path, and vice versa.
  const port = makeFakeEmbeddedAuth({ signupFailWith: 'user_exists' });
  const loginResult = await port.authenticateWithPassword('a@b.com', 'pw');
  assert.equal(loginResult.artifact, 'fake-artifact');
});
