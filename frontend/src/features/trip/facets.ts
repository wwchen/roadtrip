// One facet per active filter. A card in the list has already passed the server's
// filter, so a facet is either a match or "no data": the provider has no field for
// it, which must read differently from a miss.
import type { AmenityKey } from '@/api/generated/api-types';
import type { CampgroundSummary } from '@/api/campground-api';
import { amenityLabel, kindLabel } from '@/lib/campground-vocab';
import { filterCopy } from '@/lib/strings';
import type { CampgroundFilterState } from '@/stores/campgroundFilterStore';

export type FacetState = 'match' | 'no-data';

export interface Facet {
  key: string;
  label: string;
  state: FacetState;
}

export const SITE_TYPE_FACET = 'site_type';
export const GROUP_SIZE_FACET = 'group_size';

type ActiveFilter = Pick<CampgroundFilterState, 'siteType' | 'groupSize'> & { readonly amenities: readonly AmenityKey[] };

export function facetsFor(summary: CampgroundSummary, filter: ActiveFilter): Facet[] {
  const facets: Facet[] = [];
  if (filter.siteType) {
    facets.push({
      key: SITE_TYPE_FACET,
      label: kindLabel(filter.siteType),
      state: summary.site_total > 0 ? 'match' : 'no-data',
    });
  }
  if (filter.groupSize) {
    facets.push(
      summary.max_people != null
        ? { key: GROUP_SIZE_FACET, label: filterCopy.facetGroup(summary.max_people), state: 'match' }
        : { key: GROUP_SIZE_FACET, label: filterCopy.facetGroupNoData, state: 'no-data' },
    );
  }
  for (const key of filter.amenities) {
    const listed = summary.amenities.find((amenity) => amenity.key === key);
    facets.push(
      listed
        ? { key, label: listed.label, state: 'match' }
        : { key, label: amenityLabel(key), state: 'no-data' },
    );
  }
  return facets;
}
