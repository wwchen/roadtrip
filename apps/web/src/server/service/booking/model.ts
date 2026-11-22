import type { BookingAlias } from '@roadtrip/domain';

export const RECGOV_PROVIDER = 'recgov';

export type RefusalCategory = 'caller_action' | 'retry_later' | 'upstream';

export interface Refusal {
  category: RefusalCategory;
  code: string;
  detail?: string;
}

export type GatewayFault =
  | 'not_configured'
  | 'unanswered'
  | 'unreachable'
  | 'unauthorized'
  | 'redirected'
  | 'malformed'
  | 'rejected'
  | 'version_mismatch';

export interface StayWindow {
  startDate: string;
  endDate: string;
}

export function stayWindow(startDate: string, endDate: string): StayWindow | null {
  return endDate > startDate ? { startDate, endDate } : null;
}

export interface BookableCampsite {
  id: bigint;
  dataProvider: string;
  dataProviderRef: string;
  bookingProvider: string | null;
  bookingProviderRef: string | null;
  bookingAliases: BookingAlias[];
  campground: {
    dataProvider: string;
    dataProviderRef: string;
    bookingProvider: string | null;
    bookingProviderRef: string | null;
    bookingAliases: BookingAlias[];
  };
}

type Unavailable = { kind: 'unavailable'; fault: GatewayFault };

export type HoldResult =
  | { kind: 'held'; cartUrl: string }
  | { kind: 'blocked'; refusal: Refusal }
  | { kind: 'timed_out'; refusal: Refusal; cartUrl?: string }
  | Unavailable;

export type LoginResult =
  | { kind: 'signed_in' }
  | { kind: 'mfa_required'; challengeId: string; expiresAt?: string }
  | { kind: 'failed'; refusal: Refusal }
  | { kind: 'timed_out'; refusal: Refusal }
  | Unavailable;

export type ActionResult = { kind: 'ok' } | { kind: 'failed'; refusal: Refusal } | { kind: 'timed_out'; refusal: Refusal } | Unavailable;

export type SessionHealth =
  | { kind: 'active' }
  | { kind: 'never_logged_in' }
  | { kind: 'inactive'; code?: string }
  | { kind: 'check_failed'; code?: string; detail?: string }
  | { kind: 'unavailable'; code?: string; detail?: string; fault?: GatewayFault };

export type SignOutResult =
  | { kind: 'wiped'; signedOut: boolean }
  | { kind: 'failed'; signedOut: boolean; refusal: Refusal }
  | { kind: 'timed_out'; refusal: Refusal }
  | Unavailable;

export type VaultStatus =
  | { kind: 'none' }
  | { kind: 'stored'; username: string }
  | { kind: 'locked'; username: string }
  | { kind: 'key_changed'; username: string };

export type OpenedVault =
  | { kind: 'unavailable' }
  | { kind: 'none' }
  | { kind: 'key_changed' }
  | { kind: 'opened'; username: string; password: string };

export interface AddToCartCommand {
  campsiteId: bigint;
  window: StayWindow;
}

export type AddToCartOutcome =
  | { kind: 'held'; cartUrl: string; providerDisplay: string }
  | { kind: 'unsupported_target' }
  | { kind: 'credentials_required'; providerDisplay: string }
  | { kind: 'not_available'; providerDisplay: string }
  | { kind: 'refused'; refusal: Refusal; providerDisplay: string; cartUrl?: string }
  | { kind: 'unavailable'; fault: GatewayFault; providerDisplay: string };

export type ReaderStanding = 'sign_in_off' | 'signed_out' | 'no_credentials' | 'ready';

export type AddToCartCapability =
  | { state: 'unsupported' }
  | { state: 'signed_out' | 'no_credentials' | 'ready'; providerDisplay: string };
