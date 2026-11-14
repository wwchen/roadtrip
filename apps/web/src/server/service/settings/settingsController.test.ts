import { expect, test, vi } from 'vitest';
import { parseProfilePatch, settingsAnswer } from '../../mapper/http/settingsHttp';
import { memoryVault } from '../../testing/memoryVault';
import type { ActiveUser } from '../auth/model';
import type { SettingsPorts } from './ports';
import { settingsView, updateProfile } from './settingsController';

const USER: ActiveUser = { id: 7n, email: 'camper@example.com', emailVerified: true, displayName: 'Camper', theme: 'system', status: 'active' };
const PASSWORD = 'hunter2-recognisable';
const STORED = { username: 'rec@example.com', password: PASSWORD };
const NOTIFICATIONS = { notificationEmail: USER.email, slackChannel: null, slackConfigured: false, slackTokenHint: null };

function ports(overrides: Partial<SettingsPorts> = {}): SettingsPorts {
  return {
    vault: memoryVault(null).vault,
    adminEmails: new Set(),
    notifications: vi.fn().mockResolvedValue(NOTIFICATIONS),
    saveProfile: vi.fn().mockImplementation(async (_id, patch) => ({ ...USER, ...patch })),
    ...overrides,
  };
}

const settings = async (given: SettingsPorts) => settingsAnswer(await settingsView(USER, given));

test('settings show the profile and no stored booking credentials', async () => {
  expect((await settings(ports())).body).toEqual({
    profile: { display_name: 'Camper', login_email: 'camper@example.com', is_email_verified: true, roles: [], theme: 'system' },
    notifications: { notification_email: 'camper@example.com', slack_configured: false },
    booking: { recgov_configured: false },
  });
});

test('stored credentials show the username, never the password', async () => {
  const answer = await settings(ports({ vault: memoryVault(STORED).vault }));
  expect((answer.body as { booking: unknown }).booking).toEqual({ recgov_configured: true, recgov_username: 'rec@example.com' });
  expect(JSON.stringify(answer.body)).not.toContain(PASSWORD);
});

test('without the cipher key, stored credentials read as not configured', async () => {
  const answer = await settings(ports({ vault: memoryVault(STORED, false).vault }));
  expect((answer.body as { booking: unknown }).booking).toEqual({ recgov_configured: false });
});

test('an admin email carries the admin role', async () => {
  const answer = await settings(ports({ adminEmails: new Set(['camper@example.com']) }));
  expect((answer.body as { profile: { roles: string[] } }).profile.roles).toEqual(['admin']);
});

test('a profile update saves only the given fields and answers the settings', async () => {
  const saveProfile = vi.fn().mockImplementation(async (_id, patch) => ({ ...USER, ...patch }));
  const patch = parseProfilePatch({ theme: 'dark' });
  if (patch.kind === 'invalid') throw new Error('expected a valid patch');
  const answer = settingsAnswer(await updateProfile(USER, patch.value, ports({ saveProfile })));
  expect(saveProfile).toHaveBeenCalledWith(7n, { theme: 'dark' });
  expect(answer.body).toMatchObject({ profile: { theme: 'dark', display_name: 'Camper' } });
});

test.each([
  [{ display_name: '   ' }, 'display_name must not be blank'],
  [{ theme: 'neon' }, 'theme must be one of light, dark, system'],
  [{ display_name: 7 }, 'Request body is not valid JSON for this endpoint'],
  [undefined, 'Request body is not valid JSON for this endpoint'],
])('a bad profile update %j is refused with %s', async (body, detail) => {
  const saveProfile = vi.fn();
  const patch = parseProfilePatch(body);
  if (patch.kind === 'ok') await updateProfile(USER, patch.value, ports({ saveProfile }));
  expect(patch.kind).toBe('invalid');
  if (patch.kind === 'invalid') {
    expect(patch.answer.status).toBe(400);
    expect(patch.answer.body).toMatchObject({ detail });
  }
  expect(saveProfile).not.toHaveBeenCalled();
});
