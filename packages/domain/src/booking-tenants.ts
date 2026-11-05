import { BookingProvider } from './campground.js';
import type { BookingProviderRef } from './provider.js';

const RESERVE_VERB = 'Reserve on';
const VIEW_VERB = 'View on';
const HOST_WWW_PREFIX = 'www.';

export interface BookingTenant {
  provider: BookingProvider;
  code: string | null;
  host: string;
  displayName: string;
}

export interface BookingVendorProfile {
  provider: BookingProvider;
  displayName: string;
  sells: boolean;
  tenants: BookingTenant[];
}

function profile(
  provider: BookingProvider,
  displayName: string,
  sells: boolean,
  tenants: readonly { code?: string; host: string; displayName?: string }[],
): BookingVendorProfile {
  return {
    provider,
    displayName,
    sells,
    tenants: tenants.map((tenant) => ({
      provider,
      code: tenant.code ?? null,
      host: tenant.host,
      displayName: tenant.displayName ?? displayName,
    })),
  };
}

export const BOOKING_VENDOR_PROFILES: readonly BookingVendorProfile[] = [
  profile(BookingProvider.RECGOV, 'Recreation.gov', true, [{ host: 'www.recreation.gov' }]),
  profile(BookingProvider.CAMPFLARE, 'Campflare', false, [{ host: 'campflare.com' }]),
  profile(BookingProvider.ASPIRA, 'Aspira NextGen', true, [
    { code: 'pc', host: 'reservation.pc.gc.ca', displayName: 'Parks Canada' },
    { code: 'bc', host: 'camping.bcparks.ca', displayName: 'BC Parks' },
    { code: 'wa', host: 'washington.goingtocamp.com', displayName: 'Washington State Parks' },
  ]),
  profile(BookingProvider.RESERVEAMERICA, 'ReserveAmerica', true, [
    { code: 'ABPP', host: 'shop.albertaparks.ca', displayName: 'Alberta Parks' },
    { code: 'NY', host: 'newyorkstateparks.reserveamerica.com', displayName: 'New York State Parks' },
  ]),
  profile(BookingProvider.RESERVECALIFORNIA, 'ReserveCalifornia', true, [{ host: 'www.reservecalifornia.com' }]),
];

export function normalizeHost(host: string): string {
  const lowered = host.trim().toLowerCase();
  return lowered.startsWith(HOST_WWW_PREFIX) ? lowered.slice(HOST_WWW_PREFIX.length) : lowered;
}

function tenantCodeOf(ref: BookingProviderRef): string | null {
  switch (ref.kind) {
    case 'aspira':
      return ref.tenant;
    case 'reserveamerica':
      return ref.contractCode;
    default:
      return null;
  }
}

export class TenantRegistry {
  private readonly profiles: ReadonlyMap<BookingProvider, BookingVendorProfile>;
  private readonly byHost: ReadonlyMap<string, BookingTenant>;

  constructor(profiles: readonly BookingVendorProfile[]) {
    this.profiles = new Map(profiles.map((entry) => [entry.provider, entry]));
    if (this.profiles.size !== profiles.length) throw new Error('booking vendor profiles name a provider twice');
    this.byHost = new Map(profiles.flatMap((entry) => entry.tenants).map((tenant) => [normalizeHost(tenant.host), tenant]));
  }

  profile(provider: BookingProvider): BookingVendorProfile {
    const found = this.profiles.get(provider);
    if (found === undefined) throw new Error(`booking vendor profiles have no row for '${provider}'`);
    return found;
  }

  tenant(provider: BookingProvider, code: string | null): BookingTenant | null {
    return this.profile(provider).tenants.find((tenant) => tenant.code === code) ?? null;
  }

  tenantByHost(host: string): BookingTenant | null {
    return this.byHost.get(normalizeHost(host)) ?? null;
  }

  sells(provider: BookingProvider): boolean {
    return this.profile(provider).sells;
  }

  displayName(ref: BookingProviderRef): string {
    return this.tenant(ref.provider, tenantCodeOf(ref))?.displayName ?? this.profile(ref.provider).displayName;
  }

  ctaLabel(ref: BookingProviderRef): string {
    return `${this.sells(ref.provider) ? RESERVE_VERB : VIEW_VERB} ${this.displayName(ref)}`;
  }

  linkLabel(host: string): string | null {
    const tenant = this.tenantByHost(host);
    return tenant === null ? null : `${VIEW_VERB} ${tenant.displayName}`;
  }
}

let shipped: TenantRegistry | undefined;

export function shippedTenantRegistry(): TenantRegistry {
  shipped ??= new TenantRegistry(BOOKING_VENDOR_PROFILES);
  return shipped;
}
