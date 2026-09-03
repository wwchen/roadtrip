import { afterEach, beforeEach, expect, test, vi } from 'vitest';
import { jsonResponse, stubFetch } from '@/test/fetch-stub';
import { mountPage } from './mount';

beforeEach(() => {
  document.body.innerHTML = '';
});

afterEach(() => {
  vi.unstubAllGlobals();
});

const chromeUrls = ['/api/build-info'];

test('mounting a page starts the sandbox chrome', async () => {
  const fetched = stubFetch(jsonResponse({ env: 'prod', sha: 'abc', branch: 'master' }));
  document.body.innerHTML = '<div id="root"></div>';

  mountPage(<p>page</p>);
  await vi.waitFor(() => expect(fetched.requests.length).toBe(chromeUrls.length));

  expect(fetched.requests.map((r) => r.url).sort()).toEqual([...chromeUrls].sort());
});

test('mounting a page installs the icon sprite', async () => {
  const fetched = stubFetch(jsonResponse({ env: 'prod', sha: 'abc', branch: 'master' }));
  document.body.innerHTML = '<div id="root"></div>';

  mountPage(<p>page</p>);
  await vi.waitFor(() => expect(fetched.requests.length).toBe(chromeUrls.length));

  // A masked symbol, whose `mask="url(#k-warning-fill)"` only resolves when the
  // sprite shares a document with the glyph referencing it. See `@ui/icon-sprite`.
  expect(document.getElementById('k-warning-fill')).not.toBeNull();
});

test('the chrome still starts when the shell has no mount point, and says so', async () => {
  const fetched = stubFetch(jsonResponse({ env: 'prod', sha: 'abc', branch: 'master' }));
  const logged = vi.spyOn(console, 'error').mockImplementation(() => {});

  mountPage(<p>page</p>);
  await vi.waitFor(() => expect(fetched.requests.length).toBe(chromeUrls.length));

  expect(document.querySelector('#root')).toBeNull();
  expect(logged).toHaveBeenCalledWith(expect.stringContaining('no #root'));
  logged.mockRestore();
});

test('logs a promise rejection nobody handled', async () => {
  stubFetch(jsonResponse({ env: 'prod', sha: 'abc', branch: 'master' }));
  const logged = vi.spyOn(console, 'error').mockImplementation(() => {});
  document.body.innerHTML = '<div id="root"></div>';

  mountPage(<p>page</p>);
  const event = new Event('unhandledrejection') as Event & { reason?: unknown };
  event.reason = new Error('nobody awaited this');
  window.dispatchEvent(event);

  expect(logged).toHaveBeenCalledWith('unhandled promise rejection:', expect.any(Error));
  logged.mockRestore();
});
