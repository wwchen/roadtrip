import { appDb } from '../appDb';
import { config } from '../config';
import { updateProfile } from '../repo/appUserRepo';
import { notificationsFor } from '../repo/userSettingsRepo';
import type { SettingsPorts } from '../service/settings/ports';
import { liveVault } from './bookingAdapters';

const NO_ADMINS: ReadonlySet<string> = new Set();

export function settingsPorts(): SettingsPorts {
  return {
    vault: liveVault(),
    adminEmails: config.signIn?.adminEmails ?? NO_ADMINS,
    notifications: (userId, loginEmail) => notificationsFor(appDb(), userId, loginEmail),
    saveProfile: (userId, patch) => updateProfile(appDb(), userId, patch),
  };
}
