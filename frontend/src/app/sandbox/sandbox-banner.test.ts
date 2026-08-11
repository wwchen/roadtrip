import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest';
import { jsonResponse, stubFetch, textResponse } from '@/test/fetch-stub';
import { initSandboxBanner, renderSandboxBanner } from './sandbox-banner';

const banner = () => document.querySelector('.sandbox-banner');

beforeEach(() => {
  document.body.innerHTML = '';
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('renderSandboxBanner', () => {
  test('renders a status bar for a sandbox build', () => {
    const bar = renderSandboxBanner({ env: 'sandbox', sha: 'abc1234', branch: 'fix-foo' });

    expect(bar).not.toBeNull();
    expect(bar).toBe(banner());
    expect(bar!.getAttribute('role')).toBe('status');
    expect(bar!.textContent).toContain('SANDBOX');
  });

  test('shows the sha and branch, and links the sha to its commit', () => {
    renderSandboxBanner({ env: 'sandbox', sha: 'deadbeef', branch: 'feature-branch' });

    const sha = document.querySelector<HTMLAnchorElement>('.sandbox-banner__sha')!;
    expect(sha.textContent).toBe('deadbeef');
    expect(sha.getAttribute('href')).toBe('https://github.com/wwchen/roadtrip/commit/deadbeef');
    expect(document.querySelector('.sandbox-banner__branch')!.textContent).toBe('feature-branch');
  });

  test.each(['prod', 'local'])('renders nothing for the %s env', (env) => {
    expect(renderSandboxBanner({ env, sha: 'x', branch: 'master' })).toBeNull();
    expect(banner()).toBeNull();
  });

  test('renders nothing when there is no build info', () => {
    expect(renderSandboxBanner(null)).toBeNull();
    expect(banner()).toBeNull();
  });
});

describe('initSandboxBanner', () => {
  test('renders after /api/build-info reports a sandbox', async () => {
    const fetched = stubFetch(jsonResponse({ env: 'sandbox', sha: 'abc123', branch: 'main' }));

    await initSandboxBanner();

    expect(fetched.last.url).toBe('/api/build-info');
    expect(banner()).not.toBeNull();
  });

  test('renders nothing when the build is not a sandbox', async () => {
    stubFetch(jsonResponse({ env: 'prod', sha: 'x', branch: 'master' }));

    await initSandboxBanner();

    expect(banner()).toBeNull();
  });

  test('swallows a failed response', async () => {
    stubFetch(textResponse('nope', 500));

    await expect(initSandboxBanner()).resolves.toBeUndefined();
    expect(banner()).toBeNull();
  });

  test('swallows a network error', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(() => Promise.reject(new Error('network error'))),
    );

    await expect(initSandboxBanner()).resolves.toBeUndefined();
    expect(banner()).toBeNull();
  });
});
