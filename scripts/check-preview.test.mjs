import assert from 'node:assert/strict';
import test from 'node:test';
import { BYPASS_HEADER, checkPreview, PREVIEW_PROBE, previewFromDispatch } from './check-preview.mjs';

const BASE = 'https://roadtrip-abc-team.vercel.app';
const SECRET = 'bypass-1';
const POI = '42';

function stub(routes) {
  const requests = [];
  const fetch = async (url, init) => {
    const href = String(url);
    requests.push({ href, headers: init.headers, redirect: init.redirect });
    const route = routes[new URL(href).pathname];
    if (route === undefined) return new Response('not found', { status: 404 });
    const next = Array.isArray(route) ? route.shift() : route;
    if (next instanceof Error) throw next;
    return typeof next.body === 'string'
      ? new Response(next.body, { status: next.status })
      : Response.json(next.body, { status: next.status });
  };
  return { fetch, requests };
}

const searchFinding = (names) => ({
  status: 200,
  body: { campgrounds: names.map((name, index) => ({ name, poiId: name === PREVIEW_PROBE.name ? POI : String(index) })) },
});
const pageShowing = (text) => ({ status: 200, body: `<main><h1>${text}</h1></main>` });

const healthy = () => ({
  '/api/campgrounds/search': searchFinding(['Other', PREVIEW_PROBE.name]),
  [`/campgrounds/${POI}`]: pageShowing(PREVIEW_PROBE.name),
});

test('a preview that finds the probe and shows its page passes, sending the bypass header every time', async () => {
  const { fetch, requests } = stub(healthy());
  assert.deepEqual(await checkPreview({ baseUrl: `${BASE}/`, bypassSecret: SECRET, fetch, delayMs: 0 }), { poiId: POI });
  assert.equal(requests.length, 2);
  for (const request of requests) {
    assert.equal(request.headers[BYPASS_HEADER], SECRET);
    assert.equal(request.redirect, 'manual');
  }
  assert.equal(new URL(requests[0].href).searchParams.get('bbox'), PREVIEW_PROBE.bbox);
  assert.equal(new URL(requests[1].href).pathname, `/campgrounds/${POI}`);
});

test('a missing URL or bypass secret is refused before any request', async () => {
  const { fetch, requests } = stub(healthy());
  await assert.rejects(checkPreview({ baseUrl: '', bypassSecret: SECRET, fetch }), /PREVIEW_URL is not set/);
  await assert.rejects(checkPreview({ baseUrl: BASE, bypassSecret: '', fetch }), /VERCEL_AUTOMATION_BYPASS_SECRET is not set/);
  assert.equal(requests.length, 0);
});

test('a search that does not return the probe fails, naming what it did return', async () => {
  const { fetch } = stub({ ...healthy(), '/api/campgrounds/search': searchFinding(['Clearwater Lake']) });
  await assert.rejects(
    checkPreview({ baseUrl: BASE, bypassSecret: SECRET, fetch, delayMs: 0 }),
    /did not return Mahood Lake; it returned 1 campgrounds: Clearwater Lake/,
  );
});

test('a protection redirect or refusal fails rather than being followed', async () => {
  for (const status of [302, 401]) {
    const { fetch } = stub({ ...healthy(), '/api/campgrounds/search': { status, body: '' } });
    await assert.rejects(checkPreview({ baseUrl: BASE, bypassSecret: SECRET, fetch, delayMs: 0 }), new RegExp(`answered ${status}`));
  }
});

test('a page that answers but does not show the probe fails', async () => {
  const { fetch } = stub({ ...healthy(), [`/campgrounds/${POI}`]: pageShowing('Something else') });
  await assert.rejects(
    checkPreview({ baseUrl: BASE, bypassSecret: SECRET, fetch, delayMs: 0 }),
    /\/campgrounds\/42 does not show Mahood Lake/,
  );
});

test('a cold start is retried: a 5xx or a network error then success passes', async () => {
  const { fetch, requests } = stub({
    ...healthy(),
    '/api/campgrounds/search': [{ status: 503, body: '' }, new TypeError('fetch failed'), searchFinding([PREVIEW_PROBE.name])],
  });
  assert.deepEqual(await checkPreview({ baseUrl: BASE, bypassSecret: SECRET, fetch, delayMs: 0 }), { poiId: POI });
  assert.equal(requests.length, 4);
});

test('a preview that keeps failing gives up and reports the last failure, never the secret', async () => {
  const { fetch } = stub({ '/api/campgrounds/search': { status: 500, body: '' } });
  await assert.rejects(checkPreview({ baseUrl: BASE, bypassSecret: SECRET, fetch, delayMs: 0 }), (error) => {
    assert.match(error.message, /answered 500/);
    assert.ok(!error.message.includes(SECRET));
    return true;
  });
});

test('a preview deployment event yields its https URL and commit', () => {
  const payload = { environment: 'preview', url: 'roadtrip-abc-team.vercel.app', git: { sha: 'abc123', ref: 'feature' } };
  assert.deepEqual(previewFromDispatch(payload), { url: 'https://roadtrip-abc-team.vercel.app', sha: 'abc123' });
  assert.equal(previewFromDispatch({ ...payload, url: BASE }).url, BASE);
});

test('a production deployment event is skipped, not checked', () => {
  const payload = { environment: 'production', url: BASE, git: { sha: 'abc123' } };
  assert.match(previewFromDispatch(payload).skip, /production deployment, not a preview/);
});

test('an event of another shape fails loudly, naming the keys it has', () => {
  assert.throws(() => previewFromDispatch({ deployment: {}, target: 'preview' }), /it has: deployment, target/);
  assert.throws(() => previewFromDispatch(undefined), /without environment, url and git\.sha/);
});
