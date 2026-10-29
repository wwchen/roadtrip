import { config } from '../config';
import { serverDb } from '../db';
import type { CampgroundDetailDto, CampgroundSearchDto } from '../dto';
import { findDetailByPoi, findSummariesByPoiIds } from '../repo/campgroundReadRepo';
import { InvalidBoundaryError, searchWithinBoundary } from '../repo/campgroundSearchRepo';

export { InvalidBoundaryError };

export function getCampgroundDetail(poiId: bigint): Promise<CampgroundDetailDto | null> {
  return findDetailByPoi(serverDb(), poiId);
}

export async function searchCampgrounds(boundaryGeoJson: string): Promise<CampgroundSearchDto> {
  const db = serverDb();
  const found = await searchWithinBoundary(
    db,
    boundaryGeoJson,
    config.searchResultLimit,
    config.enabledDataProviders,
  );
  const campgrounds = await findSummariesByPoiIds(db, found.poiIds);
  return { ...found, campgrounds };
}
