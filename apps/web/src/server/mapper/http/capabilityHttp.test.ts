import { expect, test } from 'vitest';
import { capabilityDto } from './capabilityHttp';

const PROVIDER_DISPLAY = 'Recreation.gov';

test('unsupported names no provider', () => {
  expect(capabilityDto({ state: 'unsupported' })).toEqual({ state: 'unsupported' });
});

test.each(['signed_out', 'no_credentials', 'ready'] as const)('each offered state names the booking site (%s)', (state) => {
  expect(capabilityDto({ state, providerDisplay: PROVIDER_DISPLAY })).toEqual({ state, provider: 'recgov', provider_display: PROVIDER_DISPLAY });
});
