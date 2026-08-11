import { afterEach, beforeEach, expect, test, vi } from 'vitest';
import { jsonResponse, stubFetch } from '@/test/fetch-stub';
import { mountPage } from './mount';

beforeEach(() => {
  document.body.innerHTML = '';
});

afterEach(() => {
  vi.unstubAllGlobals();
});

const chromeUrls = ['/api/build-info', '/api/me', '/api/sandbox/users'];

test('mounting a page starts the sandbox chrome', async () => {
  const fetched = stubFetch(jsonResponse({ env: 'prod', sha: 'abc', branch: 'master' }));
  document.body.innerHTML = '<div id="root"></div>';

  mountPage(<p>page</p>);
  await vi.waitFor(() => expect(fetched.requests.length).toBe(chromeUrls.length));

  expect(fetched.requests.map((r) => r.url).sort()).toEqual([...chromeUrls].sort());
});

test('the chrome still starts when the shell has no mount point', async () => {
  const fetched = stubFetch(jsonResponse({ env: 'prod', sha: 'abc', branch: 'master' }));

  mountPage(<p>page</p>);
  await vi.waitFor(() => expect(fetched.requests.length).toBe(chromeUrls.length));

  expect(document.querySelector('#root')).toBeNull();
});
