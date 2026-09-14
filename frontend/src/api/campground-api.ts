import type {
  CampgroundDetailsResponseDto,
  CampgroundSearchRequestDto,
  CampgroundSearchResponseDto,
  CampgroundSummaryDto,
} from './generated/api-types';
import { jsonPostOk, type RequestOptions } from './http';

/** What the in-view list renders and filters on; the DTO, closed by codegen. */
export type CampgroundSummary = CampgroundSummaryDto;

export const CAMPGROUNDS_SEARCH_URL = '/api/campgrounds/search';
export const CAMPGROUNDS_DETAILS_URL = '/api/campgrounds/details';

export function searchCampgrounds(
  body: CampgroundSearchRequestDto,
  { signal }: RequestOptions = {},
): Promise<CampgroundSearchResponseDto> {
  return jsonPostOk<CampgroundSearchResponseDto>(CAMPGROUNDS_SEARCH_URL, body, { signal });
}

export function fetchCampgroundSummaries(
  ids: readonly number[],
  { signal }: RequestOptions = {},
): Promise<CampgroundDetailsResponseDto> {
  return jsonPostOk<CampgroundDetailsResponseDto>(CAMPGROUNDS_DETAILS_URL, { campground_ids: ids }, { signal });
}
