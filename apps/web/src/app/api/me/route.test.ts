import { expect, test } from 'vitest';
import { GET } from './route';

test('answers 200 with no identity provider', async () => {
  const response = GET();
  expect(response.status).toBe(200);
  expect(await response.json()).toEqual({ authenticated: false, auth_enabled: false, auth_embedded: false });
});
