import { config } from '../config';
import { serverDb } from '../db';
import type { CampgroundDetailDto, CampgroundSearchDto } from '../dto';
import { findDetailByPoi, findSummariesByPoiIds } from '../repo/campgroundReadRepo';
import { InvalidBoundaryError, searchWithinBoundary } from '../repo/campgroundSearchRepo';

export { InvalidBoundaryError };

export async function getCampgroundDetail(poiId: bigint): Promise<CampgroundDetailDto | null> {
  return findDetailByPoi(await serverDb(), poiId);
}

export async function searchCampgrounds(boundaryGeoJson: string): Promise<CampgroundSearchDto> {
  const db = await serverDb();
  const found = await searchWithinBoundary(
    db,
    boundaryGeoJson,
    config.searchResultLimit,
    config.enabledDataProviders,
  );
  const campgrounds = await findSummariesByPoiIds(db, found.poiIds);
  return { ...found, campgrounds };
}
