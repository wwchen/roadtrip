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
