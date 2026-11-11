import { expect, test, vi } from 'vitest';
import type { AppUserRow } from '../../repo/appUserRepo';
import { settingsFor, updateProfileSettings, type SettingsDeps } from './settingsService';

const USER: AppUserRow = { id: 7n, email: 'camper@example.com', emailVerified: true, displayName: 'Camper', theme: 'system' };
const KEY = Buffer.alloc(32, 1);

function deps(overrides: Partial<SettingsDeps> = {}): SettingsDeps {
  return {
    cipherKey: KEY,
    adminEmails: new Set(),
    credentials: vi.fn().mockResolvedValue(null),
    notifications: vi.fn().mockResolvedValue({ notification_email: USER.email, slack_configured: false }),
    saveProfile: vi.fn().mockImplementation(async (_id, patch) => ({ ...USER, ...patch })),
    ...overrides,
  };
}

test('settings show the profile and no stored booking credentials', async () => {
  expect(await settingsFor(USER, deps())).toEqual({
    profile: { display_name: 'Camper', login_email: 'camper@example.com', is_email_verified: true, roles: [], theme: 'system' },
    notifications: { notification_email: 'camper@example.com', slack_configured: false },
    booking: { recgov_configured: false },
  });
});

test('stored credentials show the username, never the password', async () => {
  const credentials = vi.fn().mockResolvedValue({ username: 'rec@example.com', secretCipher: Buffer.from([1]) });
  const settings = await settingsFor(USER, deps({ credentials }));
  expect(settings.booking).toEqual({ recgov_configured: true, recgov_username: 'rec@example.com' });
  expect(JSON.stringify(settings)).not.toContain('secret');
});

test('without the cipher key, stored credentials read as not configured', async () => {
  const credentials = vi.fn().mockResolvedValue({ username: 'rec@example.com', secretCipher: Buffer.from([1]) });
  expect((await settingsFor(USER, deps({ credentials, cipherKey: null }))).booking).toEqual({ recgov_configured: false });
});

test('an admin email carries the admin role', async () => {
  expect((await settingsFor(USER, deps({ adminEmails: new Set(['camper@example.com']) }))).profile.roles).toEqual(['admin']);
});

test('a profile update saves only the given fields and answers the settings', async () => {
  const saveProfile = vi.fn().mockImplementation(async (_id, patch) => ({ ...USER, ...patch }));
  const result = await updateProfileSettings(USER, { theme: 'dark' }, deps({ saveProfile }));
  expect(saveProfile).toHaveBeenCalledWith(7n, { theme: 'dark' });
  expect(result).toMatchObject({ ok: true, settings: { profile: { theme: 'dark', display_name: 'Camper' } } });
});

test.each([
  [{ display_name: '   ' }, 'display_name must not be blank'],
  [{ theme: 'neon' }, 'theme must be one of light, dark, system'],
  [{ display_name: 7 }, 'Request body is not valid JSON for this endpoint'],
  [undefined, 'Request body is not valid JSON for this endpoint'],
])('a bad profile update %j is refused with %s', async (body, detail) => {
  const saveProfile = vi.fn();
  const result = await updateProfileSettings(USER, body, deps({ saveProfile }));
  expect(result).toMatchObject({ ok: false, detail });
  expect(saveProfile).not.toHaveBeenCalled();
});
