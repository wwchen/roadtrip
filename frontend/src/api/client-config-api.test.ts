import { afterEach, describe, expect, test, vi } from 'vitest';
import {
  CLIENT_CONFIG_URL,
  applyClientConfig,
  cartoBasemapsApiKey,
  fetchClientConfig,
  loadClientConfig,
  resetClientConfig,
} from './client-config-api';
import { jsonResponse, stubFetch } from '@/test/fetch-stub';

afterEach(() => {
  resetClientConfig();
  vi.unstubAllGlobals();
});

describe('fetchClientConfig', () => {
  test('fetches the anonymous runtime config endpoint', async () => {
    const fetchStub = stubFetch(jsonResponse({ carto_basemaps_api_key: 'carto-test-key' }));
    const controller = new AbortController();

    const config = await fetchClientConfig({ signal: controller.signal });

    expect(fetchStub.last.url).toBe(CLIENT_CONFIG_URL);
    expect(fetchStub.last.init.signal).toBe(controller.signal);
    expect(config.carto_basemaps_api_key).toBe('carto-test-key');
  });
});

describe('client config cache', () => {
  test('loadClientConfig stores a trimmed Carto key for synchronous tile URL construction', async () => {
    stubFetch(jsonResponse({ carto_basemaps_api_key: '  carto-test-key  ' }));

    await loadClientConfig();

    expect(cartoBasemapsApiKey()).toBe('carto-test-key');
  });

  test('blank keys behave as unconfigured', () => {
    applyClientConfig({ carto_basemaps_api_key: '   ' });

    expect(cartoBasemapsApiKey()).toBeNull();
  });
});
