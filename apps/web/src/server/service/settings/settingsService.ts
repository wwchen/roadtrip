import { USER_THEMES, type UserTheme } from '@roadtrip/db';
import { appDb } from '@/server/appDb';
import { rolesFor } from '@/server/auth/currentUser';
import { config } from '@/server/config';
import type { NotificationsDto, SettingsResponseDto } from '@/server/contract';
import { updateProfile, type AppUserRow, type ProfilePatch } from '@/server/repo/appUserRepo';
import { RECGOV_PROVIDER, findCredentials, type StoredCredentials } from '@/server/repo/bookingCredentialsRepo';
import { notificationsFor } from '@/server/repo/userSettingsRepo';

export const INVALID_FIELD = 'invalid_field';
export const INVALID_BODY = 'invalid_body';
const INVALID_BODY_DETAIL = 'Request body is not valid JSON for this endpoint';
const BLANK_DISPLAY_NAME_DETAIL = 'display_name must not be blank';
const THEME_DETAIL = `theme must be one of ${USER_THEMES.join(', ')}`;
const NO_ADMINS: ReadonlySet<string> = new Set();

export interface SettingsDeps {
  cipherKey: Buffer | null;
  adminEmails: ReadonlySet<string>;
  credentials: (userId: bigint) => Promise<StoredCredentials | null>;
  notifications: (userId: bigint, loginEmail: string) => Promise<NotificationsDto>;
  saveProfile: (userId: bigint, patch: ProfilePatch) => Promise<AppUserRow>;
}

export type ProfileUpdateResult =
  | { ok: true; settings: SettingsResponseDto }
  | { ok: false; error: typeof INVALID_FIELD | typeof INVALID_BODY; detail: string };

export async function settingsFor(user: AppUserRow, deps: SettingsDeps): Promise<SettingsResponseDto> {
  const [credentials, notifications] = await Promise.all([deps.credentials(user.id), deps.notifications(user.id, user.email)]);
  const configured = deps.cipherKey !== null && credentials !== null;
  return {
    profile: {
      ...(user.displayName === null ? {} : { display_name: user.displayName }),
      login_email: user.email,
      is_email_verified: user.emailVerified,
      roles: rolesFor(user, deps.adminEmails),
      theme: user.theme,
    },
    notifications,
    booking: configured ? { recgov_configured: true, recgov_username: credentials.username } : { recgov_configured: false },
  };
}

function profilePatchOf(body: unknown): ProfilePatch | ProfileUpdateResult {
  const invalidBody: ProfileUpdateResult = { ok: false, error: INVALID_BODY, detail: INVALID_BODY_DETAIL };
  if (typeof body !== 'object' || body === null || Array.isArray(body)) return invalidBody;
  const { display_name: displayName, theme } = body as Record<string, unknown>;
  if (displayName != null && typeof displayName !== 'string') return invalidBody;
  if (theme != null && typeof theme !== 'string') return invalidBody;
  if (typeof displayName === 'string' && displayName.trim().length === 0) {
    return { ok: false, error: INVALID_FIELD, detail: BLANK_DISPLAY_NAME_DETAIL };
  }
  if (typeof theme === 'string' && !(USER_THEMES as readonly string[]).includes(theme)) {
    return { ok: false, error: INVALID_FIELD, detail: THEME_DETAIL };
  }
  return {
    ...(typeof displayName === 'string' ? { displayName } : {}),
    ...(typeof theme === 'string' ? { theme: theme as UserTheme } : {}),
  };
}

export async function updateProfileSettings(user: AppUserRow, body: unknown, deps: SettingsDeps): Promise<ProfileUpdateResult> {
  const patch = profilePatchOf(body);
  if ('ok' in patch) return patch;
  const saved = await deps.saveProfile(user.id, patch);
  return { ok: true, settings: await settingsFor(saved, deps) };
}

function liveDeps(): SettingsDeps {
  const db = appDb();
  return {
    cipherKey: config.bookingCipherKey,
    adminEmails: config.signIn?.adminEmails ?? NO_ADMINS,
    credentials: (userId) => findCredentials(db, userId, RECGOV_PROVIDER),
    notifications: (userId, loginEmail) => notificationsFor(db, userId, loginEmail),
    saveProfile: (userId, patch) => updateProfile(db, userId, patch),
  };
}

export function currentSettings(user: AppUserRow): Promise<SettingsResponseDto> {
  return settingsFor(user, liveDeps());
}

export function updateCurrentProfile(user: AppUserRow, body: unknown): Promise<ProfileUpdateResult> {
  return updateProfileSettings(user, body, liveDeps());
}
