import type { TenantRegistry } from '@roadtrip/domain';
import type { AddToCartCapabilityDto } from '@/server/contract';
import type { AppUserRow } from '@/server/repo/appUserRepo';
import { RECGOV_PROVIDER } from '@/server/repo/bookingCredentialsRepo';
import { recgovTargetFor, type BookingIdentitySource } from './bookingTarget';

const UNSUPPORTED: AddToCartCapabilityDto = { state: 'unsupported' };

export function addToCartCapability(
  campsites: readonly BookingIdentitySource[],
  campground: BookingIdentitySource,
  reader: AppUserRow | null,
  credentialed: boolean,
  tenants: TenantRegistry,
): AddToCartCapabilityDto {
  if (campsites.length === 0) return UNSUPPORTED;
  const targets = campsites.map((campsite) => recgovTargetFor(campsite, campground, tenants));
  const [first] = targets;
  if (first === undefined || first === null || targets.some((target) => target === null)) return UNSUPPORTED;
  const named = { provider: RECGOV_PROVIDER, provider_display: first.providerDisplay };
  if (reader === null) return { state: 'signed_out', ...named };
  return { state: credentialed ? 'ready' : 'no_credentials', ...named };
}
