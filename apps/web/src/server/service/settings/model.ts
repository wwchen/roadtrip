import type { UserTheme } from '@roadtrip/domain';
import type { UserProfile } from '../auth/model';

export const MFA_CHALLENGE_UNKNOWN = 'mfa_challenge_unknown';
export const MFA_CHALLENGE_EXPIRED = 'mfa_challenge_expired';

export interface MfaChallenge {
  challengeId: string;
  userId: bigint;
  expiresAt: number;
}

export type PendingChallenge = { kind: 'open'; challengeId: string } | { kind: 'none' } | { kind: 'expired' };

export type ChallengeChange = 'keep' | 'end' | { issue: MfaChallenge };

export type BookingSettings = { configured: false } | { configured: true; username: string };

export interface CredentialsCommand {
  username: string;
  password: string | null;
}

export type SaveCredentialsOutcome =
  | { kind: 'saved'; settings: BookingSettings }
  | { kind: 'password_required' }
  | { kind: 'vault_unavailable' }
  | { kind: 'wipe_failed' };

export type RemoveCredentialsOutcome =
  | { kind: 'removed'; removed: boolean; signedOut: boolean; profileDestroyed: boolean }
  | { kind: 'wipe_failed' };

export type LoginOutcome =
  | { kind: 'signed_in'; challenge: 'end' }
  | { kind: 'mfa_required'; challengeId: string; expiresAt?: string; challenge: { issue: MfaChallenge } }
  | { kind: 'failed'; code: string; detail?: string; challenge: 'keep' | 'end' }
  | { kind: 'unavailable'; challenge: 'keep' | 'end' }
  | { kind: 'challenge_gone'; reason: 'none' | 'expired' }
  | { kind: 'vault_unavailable' }
  | { kind: 'not_configured' };

export type VerifyOutcome =
  | { kind: 'verified' }
  | { kind: 'failed'; code: string; detail?: string }
  | { kind: 'unavailable' }
  | { kind: 'vault_unavailable' }
  | { kind: 'not_configured' };

export type RecgovSession = 'active' | 'not_logged_in' | 'expired' | 'check_failed' | 'companion_unavailable';

export type RecgovStatus =
  | { configured: false }
  | { configured: true; username: string; session: RecgovSession; detail?: string; mfaPending: boolean };

export interface NotificationSettings {
  notificationEmail: string;
  slackChannel: string | null;
  slackConfigured: boolean;
  slackTokenHint: string | null;
}

export interface ProfilePatch {
  displayName?: string;
  theme?: UserTheme;
}

export interface SettingsView {
  profile: UserProfile & { roles: string[] };
  notifications: NotificationSettings;
  booking: BookingSettings;
}
