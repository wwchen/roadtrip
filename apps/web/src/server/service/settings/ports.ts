import type { UserProfile } from '../auth/model';
import type { BookingGateway, Clock, CredentialVault } from '../booking/ports';
import type { NotificationSettings, ProfilePatch } from './model';

export interface RecgovPorts {
  gateway: BookingGateway;
  vault: CredentialVault;
  clock: Clock;
  mfaChallengeTtlMs: number;
}

export interface SettingsPorts {
  vault: CredentialVault;
  adminEmails: ReadonlySet<string>;
  notifications(userId: bigint, loginEmail: string): Promise<NotificationSettings>;
  saveProfile(userId: bigint, patch: ProfilePatch): Promise<UserProfile>;
}
