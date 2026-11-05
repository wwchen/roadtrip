import { expect, test } from 'vitest';
import { GET } from './route';

test('refuses an anonymous caller with 401', async () => {
  const response = GET();
  expect(response.status).toBe(401);
  expect(await response.json()).toEqual({ error: 'unauthenticated' });
});
