import { expect, test } from 'vitest';
import { GET } from './route';

const login = (query: string) => GET(new Request(`http://localhost/auth/login${query}`));

test('login redirects to the sign-in page with the return path', async () => {
  const response = await login('?return_to=/watches');
  expect(response.status).toBe(302);
  expect(response.headers.get('location')).toBe('/auth/sign-in?return_to=%2Fwatches');
});

test('a foreign return path becomes home', async () => {
  const response = await login('?return_to=//evil.example');
  expect(response.headers.get('location')).toBe('/auth/sign-in?return_to=%2F');
});
