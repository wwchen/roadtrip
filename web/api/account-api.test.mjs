import assert from 'node:assert/strict';
import test from 'node:test';

// Stub globalThis.fetch before importing the module so http.js picks up the mock.
// Each test replaces `globalThis.fetch` with a spy that records the request.

function makeFetch({ ok = true, status = 200, json = {} } = {}) {
  const spy = { lastUrl: undefined, lastInit: undefined };
  spy.fn = async (url, init) => {
    spy.lastUrl = url;
    spy.lastInit = init;
    return {
      ok,
      status,
      json: async () => {
        if (!ok && typeof json === 'function') return json();
        return json;
      },
    };
  };
  return spy;
}

// Import module under test — fetch is read at call time, not import time.
const { fetchSettings, updateProfile, updateNotifications, clearSlack, sendSlackTest } = await import('./account-api.js');

// ── fetchSettings ────────────────────────────────────────────────────────────

test('fetchSettings issues GET /api/settings', async () => {
  const spy = makeFetch({ json: { display_name: 'Alice' } });
  globalThis.fetch = spy.fn;

  const result = await fetchSettings();

  assert.equal(spy.lastUrl, '/api/settings');
  assert.equal(spy.lastInit.method, undefined); // GET is the default
  assert.deepEqual(result, { display_name: 'Alice' });
});

// ── updateProfile ─────────────────────────────────────────────────────────────

test('updateProfile issues PUT /api/settings/profile with display_name', async () => {
  const spy = makeFetch({ json: { display_name: 'Bob' } });
  globalThis.fetch = spy.fn;

  await updateProfile({ display_name: 'Bob' });

  assert.equal(spy.lastUrl, '/api/settings/profile');
  assert.equal(spy.lastInit.method, 'PUT');
  assert.deepEqual(JSON.parse(spy.lastInit.body), { display_name: 'Bob' });
  assert.equal(spy.lastInit.credentials, 'same-origin');
});

// ── updateNotifications ───────────────────────────────────────────────────────

test('updateNotifications issues PUT /api/settings/notifications', async () => {
  const spy = makeFetch();
  globalThis.fetch = spy.fn;

  await updateNotifications({ notification_email: 'a@b.com', slack_channel: '#alerts', slack_token: 'xoxb-123' });

  assert.equal(spy.lastUrl, '/api/settings/notifications');
  assert.equal(spy.lastInit.method, 'PUT');
  const body = JSON.parse(spy.lastInit.body);
  assert.equal(body.notification_email, 'a@b.com');
  assert.equal(body.slack_channel, '#alerts');
  assert.equal(body.slack_token, 'xoxb-123');
});

test('updateNotifications drops null slack_token', async () => {
  const spy = makeFetch();
  globalThis.fetch = spy.fn;

  await updateNotifications({ notification_email: 'a@b.com', slack_channel: '#alerts', slack_token: null });

  const body = JSON.parse(spy.lastInit.body);
  assert.equal(Object.prototype.hasOwnProperty.call(body, 'slack_token'), false);
  assert.equal(body.notification_email, 'a@b.com');
});

test('updateNotifications drops undefined slack_token', async () => {
  const spy = makeFetch();
  globalThis.fetch = spy.fn;

  await updateNotifications({ slack_channel: '#alerts' });

  const body = JSON.parse(spy.lastInit.body);
  assert.equal(Object.prototype.hasOwnProperty.call(body, 'slack_token'), false);
  assert.equal(body.slack_channel, '#alerts');
});

// ── clearSlack ────────────────────────────────────────────────────────────────

test('clearSlack issues DELETE /api/settings/notifications/slack', async () => {
  const spy = makeFetch({ status: 204 });
  globalThis.fetch = spy.fn;

  const result = await clearSlack();

  assert.equal(spy.lastUrl, '/api/settings/notifications/slack');
  assert.equal(spy.lastInit.method, 'DELETE');
  assert.equal(result, null);
});

// ── sendSlackTest ─────────────────────────────────────────────────────────────

test('sendSlackTest issues POST /api/settings/notifications/slack/test with channel', async () => {
  const spy = makeFetch({ json: { ok: true } });
  globalThis.fetch = spy.fn;

  await sendSlackTest('#general');

  assert.equal(spy.lastUrl, '/api/settings/notifications/slack/test');
  assert.equal(spy.lastInit.method, 'POST');
  assert.deepEqual(JSON.parse(spy.lastInit.body), { channel: '#general' });
});

test('sendSlackTest omits channel when null', async () => {
  const spy = makeFetch({ json: { ok: true } });
  globalThis.fetch = spy.fn;

  await sendSlackTest(null);

  const body = JSON.parse(spy.lastInit.body);
  assert.equal(Object.prototype.hasOwnProperty.call(body, 'channel'), false);
});

// ── error code surfacing ──────────────────────────────────────────────────────

test('a failing call surfaces .code from the error response body', async () => {
  const spy = makeFetch({ ok: false, status: 422, json: { error: 'invalid_field', detail: 'bad input' } });
  globalThis.fetch = spy.fn;

  let caught;
  try {
    await updateProfile({ display_name: '' });
  } catch (err) {
    caught = err;
  }

  assert.ok(caught, 'expected an error to be thrown');
  assert.equal(caught.status, 422);
  assert.equal(caught.code, 'invalid_field');
});

test('a failing call with non-JSON body leaves .code undefined', async () => {
  globalThis.fetch = async () => ({
    ok: false,
    status: 500,
    json: async () => { throw new SyntaxError('not json'); },
  });

  let caught;
  try {
    await fetchSettings();
  } catch (err) {
    caught = err;
  }

  assert.ok(caught, 'expected an error to be thrown');
  assert.equal(caught.status, 500);
  assert.equal(caught.code, undefined);
});
