import { afterEach, beforeEach, expect, test, vi } from 'vitest';
import { jsonResponse, stubFetch } from '@/test/fetch-stub';
import { mountPage } from './mount';

// The guarantee this suite exists for: every page gets the sandbox chrome.
//
// Until Phase 5 that was a list of `<script>`/`<link>` tags injected into each
// HTML entry by a Vite plugin, pinned by `vite/runtime-served-assets.test.ts` —
// because a page that silently shipped without the assume-user switcher looks
// signed-out in every sandbox, which is indistinguishable from a real auth
// failure. Now it is one call from the shared mount, and this is what pins it.

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

// The chrome is fixed-position furniture appended to <body>, so it does not
// depend on the React root existing — and a shell whose #root went missing is
// exactly when a reviewer needs to see which build they are looking at.
test('the chrome still starts when the shell has no mount point', async () => {
  const fetched = stubFetch(jsonResponse({ env: 'prod', sha: 'abc', branch: 'master' }));

  mountPage(<p>page</p>);
  await vi.waitFor(() => expect(fetched.requests.length).toBe(chromeUrls.length));

  expect(document.querySelector('#root')).toBeNull();
});
