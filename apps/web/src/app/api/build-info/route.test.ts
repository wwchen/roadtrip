import { afterEach, expect, test, vi } from 'vitest';
import { GET } from './route';

afterEach(() => {
  vi.unstubAllEnvs();
});

test('reports the configured build', async () => {
  vi.stubEnv('BUILD_ENV', 'production');
  vi.stubEnv('BUILD_SHA', 'deadbeef');
  vi.stubEnv('BUILD_BRANCH', 'master');
  const response = GET();
  expect(response.status).toBe(200);
  expect(await response.json()).toEqual({ env: 'production', sha: 'deadbeef', branch: 'master' });
});

test('defaults env to local', async () => {
  vi.stubEnv('BUILD_ENV', undefined);
  expect((await GET().json()).env).toBe('local');
});
