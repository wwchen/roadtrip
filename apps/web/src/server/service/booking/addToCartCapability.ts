import type { TenantRegistry } from '@roadtrip/domain';
import { recgovTargetFor, type BookingIdentitySource } from './bookingTarget';
import type { AddToCartCapability, ReaderStanding } from './model';
import type { ReaderPorts } from './ports';

const UNSUPPORTED: AddToCartCapability = { state: 'unsupported' };

export function addToCartCapability(
  campsites: readonly BookingIdentitySource[],
  campground: BookingIdentitySource,
  reader: ReaderStanding,
  tenants: TenantRegistry,
): AddToCartCapability {
  if (campsites.length === 0) return UNSUPPORTED;
  const targets = campsites.map((campsite) => recgovTargetFor(campsite, campground, tenants));
  const [first] = targets;
  if (first === undefined || first === null || targets.some((target) => target === null)) return UNSUPPORTED;
  if (reader === 'sign_in_off') return UNSUPPORTED;
  return { state: reader, providerDisplay: first.providerDisplay };
}

export async function readerStanding(ports: ReaderPorts): Promise<ReaderStanding> {
  if (!ports.signInConfigured()) return 'sign_in_off';
  const user = await ports.currentUser();
  if (user === null) return 'signed_out';
  const status = await ports.vault.status(user.id);
  return status.kind === 'stored' ? 'ready' : 'no_credentials';
}
