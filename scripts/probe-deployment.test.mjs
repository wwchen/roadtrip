import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { mkdtempSync, rmSync, symlinkSync } from 'node:fs';
import { createServer } from 'node:http';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';
import {
  BYPASS_HEADER,
  awaitSha,
  catalog,
  main,
  readConfig,
  surface,
} from './probe-deployment.mjs';

const NAME = 'Mahood Lake';
const BBOX = '-120.2,52.1,-120.0,52.3';
const SHA = 'abc123';
const SECRET = 'bypass-value';
const SHORT_AWAIT_SECONDS = '0.05';
const LONG_AWAIT_SECONDS = '5';
const STALE_POLLS_BEFORE_MATCH = 2;
const SERVICE_UNAVAILABLE = 503;
const FOUND = 302;
const RETRIES = 1;
const REQUESTS_WITH_ONE_RETRY = 2;
const REDIRECT_TARGET = '/redirect-target';
const UNKNOWN_COMMAND = 'bogus';
const LINK_DIR_PREFIX = 'probe-link-';
const LINK_NAME = 'probe-link.mjs';
const SCRIPT_PATH = fileURLToPath(new URL('./probe-deployment.mjs', import.meta.url));
const FAST = { retryCount: 0, retryDelayMs: 1, pollIntervalMs: 5, requestTimeoutMs: 2_000 };

function goodRoutes() {
  return {
    '/': { type: 'text/html; charset=utf-8', body: '<html></html>' },
    '/api/me': { json: { authenticated: false } },
    '/api/availability/pollers/summary': { json: { pollers: [] } },
    '/api/docs/openapi.json': { json: { openapi: '3.1.0', paths: {} } },
    '/api/campgrounds/search': { json: { campgrounds: [{ name: NAME, poiId: 'p1' }] } },
    '/campgrounds/p1': { type: 'text/html', body: `<h1>${NAME}</h1>` },
    '/api/build-info': { json: { sha: SHA } },
  };
}

async function withStub(routes, run) {
  const seen = [];
  const paths = [];
  const server = createServer((request, response) => {
    const route = routes[new URL(request.url, 'http://stub').pathname];
    seen.push(request.headers);
    paths.push(new URL(request.url, 'http://stub').pathname);
    if (!route) return response.writeHead(404).end();
    const entry = typeof route === 'function' ? route() : route;
    response.writeHead(entry.status ?? 200, {
      'content-type': entry.json ? 'application/json' : entry.type,
      ...entry.headers,
    });
    response.end(entry.json ? JSON.stringify(entry.json) : entry.body);
  });
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  try {
    return await run({ seen, paths, baseUrl: `http://127.0.0.1:${server.address().port}/` });
  } finally {
    server.close();
  }
}

const configFor = (stub, extra = {}, overrides = {}) =>
  readConfig(
    { BASE_URL: stub.baseUrl, PROBE_NAME: NAME, PROBE_BBOX: BBOX, EXPECTED_SHA: SHA, ...extra },
    { ...FAST, ...overrides },
  );
const withRetry = { retryCount: RETRIES };

test('catalog passes on a good stub', () =>
  withStub(goodRoutes(), async (stub) => {
    assert.match(await catalog(configFor(stub)), /serves Mahood Lake \(poi p1\)/);
  }));

test('catalog fails when search lacks the name', () =>
  withStub({ ...goodRoutes(), '/api/campgrounds/search': { json: { campgrounds: [] } } }, async (stub) => {
    await assert.rejects(catalog(configFor(stub)), /did not return Mahood Lake/);
  }));

test('catalog fails when the page lacks the name', () =>
  withStub({ ...goodRoutes(), '/campgrounds/p1': { type: 'text/html', body: 'nothing' } }, async (stub) => {
    await assert.rejects(catalog(configFor(stub)), /does not show Mahood Lake/);
  }));

test('surface passes on a good stub', () =>
  withStub(goodRoutes(), async (stub) => {
    await surface(configFor(stub));
  }));

const WRONG_SHAPES = [
  ['/', { type: 'text/plain', body: 'hi' }, /content type/],
  ['/api/me', { json: { authenticated: 'yes' } }, /boolean authenticated/],
  ['/api/availability/pollers/summary', { json: [] }, /JSON object/],
  ['/api/docs/openapi.json', { json: { openapi: 3, paths: {} } }, /string openapi/],
  ['/api/docs/openapi.json', { json: { openapi: '3.1.0', paths: [] } }, /object paths/],
];

for (const [path, broken, message] of WRONG_SHAPES) {
  test(`surface fails on a wrong shape at ${path}`, () =>
    withStub({ ...goodRoutes(), [path]: broken }, async (stub) => {
      await assert.rejects(surface(configFor(stub)), message);
    }));
}

for (const path of ['/', '/api/me', '/api/availability/pollers/summary', '/api/docs/openapi.json']) {
  test(`surface fails on a wrong status at ${path}`, () =>
    withStub({ ...goodRoutes(), [path]: { status: 403, type: 'text/plain', body: '' } }, async (stub) => {
      await assert.rejects(surface(configFor(stub)), new RegExp(`${path} answered 403`));
    }));
}

test('await-sha succeeds once the stub reports the expected sha', () => {
  let polls = 0;
  const buildInfo = () => ({ json: { sha: ++polls > STALE_POLLS_BEFORE_MATCH ? SHA : 'old' } });
  return withStub({ ...goodRoutes(), '/api/build-info': buildInfo }, async (stub) => {
    await awaitSha(configFor(stub, { AWAIT_TIMEOUT_SECONDS: LONG_AWAIT_SECONDS }));
    assert.equal(polls, STALE_POLLS_BEFORE_MATCH + 1);
  });
});

test('await-sha times out naming the last sha seen', () =>
  withStub({ ...goodRoutes(), '/api/build-info': { json: { sha: 'old' } } }, async (stub) => {
    await assert.rejects(awaitSha(configFor(stub, { AWAIT_TIMEOUT_SECONDS: SHORT_AWAIT_SECONDS })), /last saw old/);
  }));

test('the bypass header is sent only when the secret is set', () =>
  withStub(goodRoutes(), async (stub) => {
    await surface(configFor(stub));
    assert.ok(stub.seen.length > 0);
    assert.ok(stub.seen.every((headers) => !(BYPASS_HEADER in headers)));
    stub.seen.length = 0;
    await surface(configFor(stub, { VERCEL_AUTOMATION_BYPASS_SECRET: SECRET }));
    assert.ok(stub.seen.length > 0);
    assert.ok(stub.seen.every((headers) => headers[BYPASS_HEADER] === SECRET));
  }));

const unavailable = { status: SERVICE_UNAVAILABLE, type: 'text/plain', body: '' };

test('a retry recovers from a server error', () => {
  let calls = 0;
  const flaky = () => (++calls === 1 ? unavailable : { json: { authenticated: false } });
  return withStub({ ...goodRoutes(), '/api/me': flaky }, async (stub) => {
    await surface(configFor(stub, {}, withRetry));
    assert.equal(calls, REQUESTS_WITH_ONE_RETRY);
  });
});

test('a retry gives up on a persistent server error', () => {
  let calls = 0;
  const down = () => {
    calls += 1;
    return unavailable;
  };
  return withStub({ ...goodRoutes(), '/api/me': down }, async (stub) => {
    await assert.rejects(surface(configFor(stub, {}, withRetry)), /answered 503/);
    assert.equal(calls, REQUESTS_WITH_ONE_RETRY);
  });
});

test('redirects are not followed', () =>
  withStub(
    { ...goodRoutes(), '/': { status: FOUND, headers: { location: REDIRECT_TARGET }, type: 'text/plain', body: '' } },
    async (stub) => {
      await assert.rejects(surface(configFor(stub)), /answered 302/);
      assert.ok(!stub.paths.includes(REDIRECT_TARGET));
    },
  ));

test('catalog fails on a null poiId without requesting a page', () =>
  withStub(
    { ...goodRoutes(), '/api/campgrounds/search': { json: { campgrounds: [{ name: NAME, poiId: null }] } } },
    async (stub) => {
      await assert.rejects(catalog(configFor(stub)), /did not return Mahood Lake/);
      assert.ok(!stub.paths.some((path) => path.startsWith('/campgrounds/')));
    },
  ));

test('an unknown command is rejected with usage', async () => {
  await assert.rejects(main([UNKNOWN_COMMAND], {}), /usage/);
});

test('the entry point runs when invoked through a symlink', () => {
  const directory = mkdtempSync(join(tmpdir(), LINK_DIR_PREFIX));
  try {
    const link = join(directory, LINK_NAME);
    symlinkSync(SCRIPT_PATH, link);
    const result = spawnSync(process.execPath, [link, UNKNOWN_COMMAND], { env: {}, encoding: 'utf8' });
    assert.notEqual(result.status, 0);
    assert.match(result.stderr, /usage/);
  } finally {
    rmSync(directory, { recursive: true, force: true });
  }
});
