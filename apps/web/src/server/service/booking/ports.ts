import type { TenantRegistry } from '@roadtrip/domain';
import type { ActiveUser } from '../auth/model';
import type {
  ActionResult,
  BookableCampsite,
  HoldResult,
  LoginResult,
  OpenedVault,
  SessionHealth,
  SignOutResult,
  StayWindow,
  VaultStatus,
} from './model';

export interface BookingGateway {
  hold(userId: bigint, vendorSiteId: string, window: StayWindow): Promise<HoldResult>;
  signIn(userId: bigint, username: string, password: string): Promise<LoginResult>;
  submitMfa(userId: bigint, challengeId: string, code: string): Promise<LoginResult>;
  refresh(userId: bigint): Promise<ActionResult>;
  verify(userId: bigint): Promise<ActionResult>;
  health(userId: bigint): Promise<SessionHealth>;
  signOut(userId: bigint): Promise<SignOutResult>;
}

export interface CredentialVault {
  status(userId: bigint): Promise<VaultStatus>;
  open(userId: bigint): Promise<OpenedVault>;
  store(userId: bigint, username: string, password: string): Promise<'stored' | 'unavailable'>;
  rename(userId: bigint, username: string): Promise<void>;
  remove(userId: bigint): Promise<boolean>;
}

export interface CampsiteCatalog {
  bookableCampsite(campsiteId: bigint): Promise<BookableCampsite | null>;
}

export interface StoredAvailability {
  takenIn(campsite: BookableCampsite, window: StayWindow): Promise<boolean>;
}

export interface Clock {
  now(): number;
}

export interface BookingPorts {
  gateway: BookingGateway;
  vault: CredentialVault;
  catalog: CampsiteCatalog;
  stored: StoredAvailability;
  tenants: TenantRegistry;
}

export interface ReaderPorts {
  signInConfigured(): boolean;
  currentUser(): Promise<ActiveUser | null>;
  vault: Pick<CredentialVault, 'status'>;
}
