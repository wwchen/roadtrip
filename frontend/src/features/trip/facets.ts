// One facet per active filter. A card in the list has passed the server's
// filter, so a facet is a match (data exists and filter matches) or "no data"
// (provider has no field for it, which reads differently from miss).
// A miss should not appear, but the facet stays honest if one does.
import type { AmenityKey } from '@/api/generated/api-types';
import type { CampgroundSummary } from '@/api/campground-api';
import { amenityLabel, kindLabel } from '@/lib/campground-vocab';
import { filterCopy } from '@/lib/strings';
import type { CampgroundFilterState } from '@/stores/campgroundFilterStore';

export type FacetState = 'match' | 'miss' | 'no-data';

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
    const state: FacetState =
      summary.site_total === 0
        ? 'no-data'
        : (summary.site_counts[filter.siteType] ?? 0) > 0
          ? 'match'
          : 'miss';
    facets.push({
      key: SITE_TYPE_FACET,
      label: kindLabel(filter.siteType),
      state,
    });
  }
  if (filter.groupSize) {
    facets.push(
      summary.max_people != null
        ? {
            key: GROUP_SIZE_FACET,
            label: filterCopy.facetGroup(summary.max_people),
            state: summary.max_people >= filter.groupSize ? 'match' : 'miss',
          }
        : { key: GROUP_SIZE_FACET, label: filterCopy.facetGroupNoData, state: 'no-data' },
    );
  }
  for (const key of filter.amenities) {
    const listed = summary.amenities.find((amenity) => amenity.key === key);
    if (listed) {
      facets.push({ key, label: listed.label, state: listed.present ? 'match' : 'miss' });
    } else {
      facets.push({ key, label: amenityLabel(key), state: 'no-data' });
    }
  }
  return facets;
}
