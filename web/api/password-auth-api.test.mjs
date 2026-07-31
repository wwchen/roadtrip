// web/api/password-auth-api.test.mjs
import assert from 'node:assert/strict';
import test from 'node:test';
import { beginPasswordLogin, completePasswordLogin } from './password-auth-api.js';

test('beginPasswordLogin POSTs return_to and returns the flow material', async () => {
  const calls = [];
  const fakeFetch = async (url, opts) => {
    calls.push({ url, opts });
    return { ok: true, status: 200, json: async () => ({ state: 's', nonce: 'n', code_challenge: 'c' }) };
  };
  const out = await beginPasswordLogin('/watches', { _fetch: fakeFetch });
  assert.equal(out.code_challenge, 'c');
  assert.match(calls[0].url, /\/auth\/password\/begin/);
  assert.equal(calls[0].opts.method, 'POST');
  assert.deepEqual(JSON.parse(calls[0].opts.body), { return_to: '/watches' });
});

test('completePasswordLogin POSTs code+state and resolves on 204', async () => {
  const calls = [];
  const fakeFetch = async (url, opts) => { calls.push({ url, opts }); return { ok: true, status: 204, json: async () => null }; };
  const out = await completePasswordLogin('code-1', 'st-1', '/', { _fetch: fakeFetch });
  assert.equal(out, null);
  assert.deepEqual(JSON.parse(calls[0].opts.body), { code: 'code-1', state: 'st-1', return_to: '/' });
});

test('completePasswordLogin rejects with .code on error body', async () => {
  const fakeFetch = async () => ({ ok: false, status: 401, json: async () => ({ error: 'login_failed' }) });
  await assert.rejects(
    () => completePasswordLogin('bad', 'st', '/', { _fetch: fakeFetch }),
    (err) => err.code === 'login_failed',
  );
});
