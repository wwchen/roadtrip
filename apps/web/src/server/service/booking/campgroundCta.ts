import { BookingProvider, type BookingProviderRef, type TenantRegistry } from '@roadtrip/domain';
import type { PoiCtaSchema } from '../../contract';
import { aspiraBookingUrl, campflareCampgroundUrl, recGovCampgroundUrl, reserveCaliforniaParkUrl } from './bookingUrls';
import type { PoiDateContext } from './dateContext';
import { infoLinkLabel, urlHost } from './infoLinkLabels';

const INFO_CTA_KIND = 'info';
const RESERVE_CTA_KIND = 'reserve';
const DEEPLINK_NIGHTS = 1;

export interface CtaInputs {
  bookingRef: BookingProviderRef | null;
  reserveUrl: string | null;
  infoUrl: string | null;
  dateContext: PoiDateContext;
  identities: readonly BookingProviderRef[];
}

function nonBlank(value: string | null): string | null {
  return value !== null && value.trim().length > 0 ? value : null;
}

const reserveCta = (url: string, label: string): PoiCtaSchema => ({ url, label, kind: RESERVE_CTA_KIND });
const infoCta = (tenants: TenantRegistry, url: string): PoiCtaSchema => ({ url, label: infoLinkLabel(tenants, url), kind: INFO_CTA_KIND });

function recGovCtas(tenants: TenantRegistry, ref: BookingProviderRef, upstreamUrl: string | null): PoiCtaSchema[] {
  if (ref.kind !== 'recgov') return [];
  const host = upstreamUrl === null ? null : urlHost(upstreamUrl);
  const stored = host !== null && tenants.tenantByHost(host)?.provider === BookingProvider.RECGOV ? upstreamUrl : null;
  const reserve = reserveCta(stored ?? recGovCampgroundUrl(ref.facilityId), tenants.ctaLabel(ref));
  return upstreamUrl !== null && stored === null ? [reserve, infoCta(tenants, upstreamUrl)] : [reserve];
}

function aspiraCtas(tenants: TenantRegistry, ref: BookingProviderRef, dateContext: PoiDateContext): PoiCtaSchema[] {
  if (ref.kind !== 'aspira') return [];
  const host = tenants.tenant(BookingProvider.ASPIRA, ref.tenant)?.host;
  if (host === undefined) return [];
  return [reserveCta(aspiraBookingUrl(host, ref, dateContext.earliestDate, DEEPLINK_NIGHTS), tenants.ctaLabel(ref))];
}

function reserveCaliforniaCtas(tenants: TenantRegistry, ref: BookingProviderRef): PoiCtaSchema[] {
  if (ref.kind !== 'reservecalifornia') return [];
  return [reserveCta(reserveCaliforniaParkUrl(ref.placeId), tenants.ctaLabel(ref))];
}

function vendorCtas(tenants: TenantRegistry, inputs: CtaInputs): PoiCtaSchema[] {
  const upstreamUrl = nonBlank(inputs.reserveUrl) ?? nonBlank(inputs.infoUrl);
  const ref = inputs.bookingRef;
  if (ref !== null) {
    const claimed = [
      () => recGovCtas(tenants, ref, upstreamUrl),
      () => aspiraCtas(tenants, ref, inputs.dateContext),
      () => reserveCaliforniaCtas(tenants, ref),
    ]
      .map((provider) => provider())
      .find((ctas) => ctas.length > 0);
    if (claimed !== undefined) return claimed;
  }
  const info = nonBlank(inputs.infoUrl);
  return info === null ? [] : [infoCta(tenants, info)];
}

function campflareCta(tenants: TenantRegistry, refs: readonly BookingProviderRef[]): PoiCtaSchema | null {
  const campflare = refs.find((ref) => ref.kind === 'campflare');
  if (campflare === undefined || campflare.kind !== 'campflare') return null;
  return { url: campflareCampgroundUrl(campflare.campgroundId), label: tenants.ctaLabel(campflare), kind: INFO_CTA_KIND };
}

export function computeCtas(tenants: TenantRegistry, inputs: CtaInputs): PoiCtaSchema[] {
  const identities = inputs.bookingRef === null ? inputs.identities : [inputs.bookingRef, ...inputs.identities];
  const all = [...vendorCtas(tenants, inputs), campflareCta(tenants, identities)].filter((cta): cta is PoiCtaSchema => cta !== null);
  const seen = new Set<string>();
  const distinct: PoiCtaSchema[] = [];
  for (const cta of all) {
    if (seen.has(cta.url)) continue;
    seen.add(cta.url);
    distinct.push(cta);
  }
  return distinct;
}

export function bookingSystem(tenants: TenantRegistry, ref: BookingProviderRef | null): string | null {
  return ref === null ? null : tenants.displayName(ref);
}

export function campgroundBookingRef(tenants: TenantRegistry, identities: readonly BookingProviderRef[]): BookingProviderRef | null {
  return identities.find((identity) => tenants.sells(identity.provider)) ?? identities[0] ?? null;
}
