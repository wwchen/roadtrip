import { BookingProvider, bookingIdentities, type BookingAlias, type TenantRegistry } from '@roadtrip/domain';

export interface BookingIdentitySource {
  bookingProvider: string | null;
  bookingProviderRef: string | null;
  bookingAliases: readonly BookingAlias[];
}

export interface RecgovTarget {
  vendorSiteId: string;
  providerDisplay: string;
}

function recgovSiteRef(campsite: BookingIdentitySource): string | null {
  const identities: { provider: string | null; ref: string | null }[] = [
    { provider: campsite.bookingProvider, ref: campsite.bookingProviderRef },
    ...campsite.bookingAliases,
  ];
  const ref = identities.find((identity) => identity.provider === BookingProvider.RECGOV && (identity.ref ?? '').trim().length > 0)?.ref;
  return ref === undefined || ref === null ? null : ref.trim();
}

export function recgovTargetFor(campsite: BookingIdentitySource, campground: BookingIdentitySource, tenants: TenantRegistry): RecgovTarget | null {
  const vendorSiteId = recgovSiteRef(campsite);
  if (vendorSiteId === null) return null;
  const parent = bookingIdentities(campground.bookingProvider, campground.bookingProviderRef, campground.bookingAliases).find(
    (ref) => ref.provider === BookingProvider.RECGOV,
  );
  if (parent === undefined) return null;
  return { vendorSiteId, providerDisplay: tenants.displayName(parent) };
}
