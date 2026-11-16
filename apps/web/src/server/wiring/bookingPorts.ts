import { shippedTenantRegistry } from '@roadtrip/domain';
import { storedAvailability } from '../client/storedAvailability';
import { AVAILABILITY_STORED_TIMEOUT_MS, config } from '../config';
import { serverDb } from '../db';
import { findBookableCampsite } from '../repo/campsiteCatalogRepo';
import { configuredEgress } from '../service/availability/availabilityMatrixService';
import type { BookingPorts } from '../service/booking/ports';
import { liveGateway, liveVault } from './bookingAdapters';

const DEFAULT_FRESHNESS_MAX_AGE_MS = 300_000;

export function bookingPorts(): BookingPorts {
  const freshnessMaxAgeMs = config.booking?.freshnessMaxAgeMs ?? DEFAULT_FRESHNESS_MAX_AGE_MS;
  return {
    gateway: liveGateway(),
    vault: liveVault(),
    catalog: { bookableCampsite: async (campsiteId) => findBookableCampsite(await serverDb(), campsiteId) },
    stored: storedAvailability(configuredEgress(AVAILABILITY_STORED_TIMEOUT_MS), { now: Date.now }, freshnessMaxAgeMs),
    tenants: shippedTenantRegistry(),
  };
}
