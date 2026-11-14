import type { ActiveUser, UserProfile } from '../auth/model';
import type { BookingSettings, ProfilePatch, SettingsView } from './model';
import type { SettingsPorts } from './ports';

const ADMIN_ROLE = 'admin';

function rolesOf(profile: UserProfile, adminEmails: ReadonlySet<string>): string[] {
  return profile.emailVerified && adminEmails.has(profile.email.toLowerCase()) ? [ADMIN_ROLE] : [];
}

async function viewOf(profile: UserProfile, ports: SettingsPorts): Promise<SettingsView> {
  const [vault, notifications] = await Promise.all([ports.vault.status(profile.id), ports.notifications(profile.id, profile.email)]);
  const booking: BookingSettings = vault.kind === 'stored' ? { configured: true, username: vault.username } : { configured: false };
  return { profile: { ...profile, roles: rolesOf(profile, ports.adminEmails) }, notifications, booking };
}

export function settingsView(user: ActiveUser, ports: SettingsPorts): Promise<SettingsView> {
  return viewOf(user, ports);
}

export async function updateProfile(user: ActiveUser, patch: ProfilePatch, ports: SettingsPorts): Promise<SettingsView> {
  return viewOf(await ports.saveProfile(user.id, patch), ports);
}
