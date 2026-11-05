import table from './availability-providers.json' with { type: 'json' };
import { BookingProvider } from './campground.js';
import type { TenantRegistry } from './booking-tenants.js';
import type { BookingProviderRef } from './provider.js';

export type AvailabilityProviderId =
  | typeof BookingProvider.RECGOV
  | typeof BookingProvider.CAMPFLARE
  | typeof BookingProvider.RESERVECALIFORNIA
  | typeof BookingProvider.ASPIRA
  | typeof BookingProvider.RESERVEAMERICA;

export interface AvailabilityProviderCapabilities {
  id: AvailabilityProviderId;
  bookingHorizonDays: number;
  maxPollWindowDays: number;
}

export const AVAILABILITY_PROVIDERS: readonly AvailabilityProviderCapabilities[] = table as AvailabilityProviderCapabilities[];

function tenantKnown(ref: BookingProviderRef, tenants: TenantRegistry): boolean {
  switch (ref.kind) {
    case 'aspira':
      return tenants.tenant(ref.provider, ref.tenant) !== null;
    case 'reserveamerica':
      return tenants.tenant(ref.provider, ref.contractCode) !== null;
    default:
      return true;
  }
}

function claims(provider: AvailabilityProviderCapabilities, identities: readonly BookingProviderRef[], tenants: TenantRegistry): boolean {
  const claimed = identities.find((identity) => identity.provider === provider.id);
  return claimed !== undefined && tenantKnown(claimed, tenants);
}

export function servingAvailabilityProvider(
  identities: readonly BookingProviderRef[],
  enabled: readonly string[],
  tenants: TenantRegistry,
): AvailabilityProviderCapabilities | null {
  return AVAILABILITY_PROVIDERS.find((provider) => enabled.includes(provider.id) && claims(provider, identities, tenants)) ?? null;
}
