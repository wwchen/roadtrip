import type { Db } from '@roadtrip/db';
import { bookingIdentities, shippedTenantRegistry } from '@roadtrip/domain';
import type { CampgroundSearchQuery } from '../campgroundSearchRequest';
import { config } from '../config';
import type { CampgroundDetailsResponseDto, CampgroundSearchResponseDto, CampgroundSummaryDto } from '../contract';
import { toContractId } from '../contractIds';
import { serverDb } from '../db';
import { searchWithinBoundary } from '../repo/campgroundSearchRepo';
import { findCampgroundSummaries, type CampgroundSummaryRow } from '../repo/campgroundSummaryRepo';
import { bookingSystem, campgroundBookingRef } from './booking/campgroundCta';
import { amenityDtos, ratingDto } from './campgroundContract';

export async function searchCampgroundIds(
  db: Db,
  query: CampgroundSearchQuery,
  limit: number,
  providers: readonly string[],
): Promise<CampgroundSearchResponseDto> {
  const found = await searchWithinBoundary(db, query.boundaryGeoJson, limit, providers, query.filter);
  return {
    campground_ids: found.poiIds.map((id) => toContractId(BigInt(id))),
    total_in_boundary: found.totalInBoundary,
    total_matching: found.totalMatching,
    truncated: found.truncated,
  };
}

function summaryBooking(row: CampgroundSummaryRow): { system: string | null; supported: boolean } {
  const tenants = shippedTenantRegistry();
  const bookingRef = campgroundBookingRef(tenants, bookingIdentities(row.bookingProvider, row.bookingProviderRef, row.bookingAliases));
  return { system: bookingSystem(tenants, bookingRef), supported: bookingRef !== null };
}

function summaryDto(row: CampgroundSummaryRow): CampgroundSummaryDto {
  const rating = ratingDto(row.metadata?.rating);
  const booking = summaryBooking(row);
  return {
    id: toContractId(row.poiId),
    campground_id: toContractId(row.campgroundId),
    name: row.name,
    ...(row.region === null ? {} : { region: row.region }),
    ...(row.agency === null ? {} : { agency: row.agency }),
    lng: row.lng,
    lat: row.lat,
    ...(rating === undefined ? {} : { rating }),
    availability_supported: booking.supported,
    ...(booking.system === null ? {} : { booking_system: booking.system }),
    amenities: amenityDtos(row.amenities),
    site_counts: row.siteCounts,
    site_total: row.siteTotal,
    ...(row.maxPeople === null ? {} : { max_people: row.maxPeople }),
  };
}

export async function campgroundDetails(db: Db, ids: readonly bigint[], providers: readonly string[]): Promise<CampgroundDetailsResponseDto> {
  return { campgrounds: (await findCampgroundSummaries(db, ids, providers)).map(summaryDto) };
}

export async function searchCampgroundsByContract(query: CampgroundSearchQuery): Promise<CampgroundSearchResponseDto> {
  return searchCampgroundIds(await serverDb(), query, config.campgroundSearchMaxResults, config.enabledDataProviders);
}

export async function getCampgroundDetails(ids: readonly bigint[]): Promise<CampgroundDetailsResponseDto> {
  return campgroundDetails(await serverDb(), ids, config.enabledDataProviders);
}
