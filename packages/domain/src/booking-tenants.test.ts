import { describe, expect, test } from 'vitest';
import { BookingProviderRef } from './provider.js';
import { BOOKING_VENDOR_PROFILES, shippedTenantRegistry } from './booking-tenants.js';

const tenants = shippedTenantRegistry();
const parksCanada = BookingProviderRef.aspira('pc', 1, 2, null);
const bcParks = BookingProviderRef.aspira('bc', 1, 2, null);
const washington = BookingProviderRef.aspira('wa', 1, 2, null);
const unknownTenant = BookingProviderRef.aspira('zz', 1, 2, null);

describe('displayName', () => {
  test('names the tenant, not the vendor', () => {
    expect(tenants.displayName(parksCanada)).toBe('Parks Canada');
    expect(tenants.displayName(bcParks)).toBe('BC Parks');
    expect(tenants.displayName(washington)).toBe('Washington State Parks');
    expect(tenants.displayName(BookingProviderRef.recGov('232450'))).toBe('Recreation.gov');
    expect(tenants.displayName(BookingProviderRef.campflare('9'))).toBe('Campflare');
    expect(tenants.displayName(BookingProviderRef.reserveAmerica('ABPP', '10'))).toBe('Alberta Parks');
    expect(tenants.displayName(BookingProviderRef.reserveAmerica('NY', '10'))).toBe('New York State Parks');
    expect(tenants.displayName(BookingProviderRef.reserveCalifornia(1, [2]))).toBe('ReserveCalifornia');
  });

  test('falls back to the vendor name for a tenant the registry does not name', () => {
    expect(tenants.displayName(unknownTenant)).toBe('Aspira NextGen');
  });
});

describe('labels', () => {
  test('a selling vendor reserves and a non-selling one views', () => {
    expect(tenants.ctaLabel(bcParks)).toBe('Reserve on BC Parks');
    expect(tenants.ctaLabel(BookingProviderRef.campflare('9'))).toBe('View on Campflare');
  });

  test('a host link always views, matching hosts without case or www', () => {
    expect(tenants.linkLabel('RECREATION.gov')).toBe('View on Recreation.gov');
    expect(tenants.linkLabel('www.camping.bcparks.ca')).toBe('View on BC Parks');
    expect(tenants.linkLabel('example.com')).toBeNull();
  });
});

describe('lookup', () => {
  test('tenant codes match verbatim', () => {
    expect(tenants.tenant('aspira', 'bc')?.host).toBe('camping.bcparks.ca');
    expect(tenants.tenant('aspira', 'BC')).toBeNull();
    expect(tenants.tenantByHost('www.recreation.gov')?.provider).toBe('recgov');
  });

  test('every booking provider has exactly one profile', () => {
    expect(BOOKING_VENDOR_PROFILES.map((profile) => profile.provider).sort()).toEqual(
      ['aspira', 'campflare', 'recgov', 'reserveamerica', 'reservecalifornia'],
    );
  });
});
