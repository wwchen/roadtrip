import { expect, test, vi } from 'vitest';

const requireAppUser = vi.fn();
const updateCurrentProfile = vi.fn();
vi.mock('@/server/auth/currentUser', () => ({ requireAppUser }));
vi.mock('@/server/service/settings/settingsService', () => ({ updateCurrentProfile }));
const { PUT } = await import('./route');

const put = (body: string) => PUT(new Request('http://localhost/api/settings/profile', { method: 'PUT', body }));

test('an anonymous caller gets 401', async () => {
  requireAppUser.mockResolvedValue(Response.json({ error: 'unauthenticated' }, { status: 401 }));
  expect((await put('{}')).status).toBe(401);
});

test('a refused field is 400 invalid_field with its detail', async () => {
  requireAppUser.mockResolvedValue({ id: 7n });
  updateCurrentProfile.mockResolvedValue({ ok: false, error: 'invalid_field', detail: 'theme must be one of light, dark, system' });
  const response = await put('{"theme":"neon"}');
  expect(response.status).toBe(400);
  expect(await response.json()).toEqual({ error: 'invalid_field', detail: 'theme must be one of light, dark, system' });
});

test('an accepted update answers the settings', async () => {
  requireAppUser.mockResolvedValue({ id: 7n });
  updateCurrentProfile.mockResolvedValue({ ok: true, settings: { profile: { theme: 'dark' } } });
  const response = await put('{"theme":"dark"}');
  expect(response.status).toBe(200);
  expect(await response.json()).toEqual({ profile: { theme: 'dark' } });
  expect(updateCurrentProfile.mock.calls[0]![1]).toEqual({ theme: 'dark' });
});
