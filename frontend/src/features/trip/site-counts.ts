// How many sites a campground has, from its campsite catalog.
//
// `GET /api/pois/{id}` carries no site count, so the in-view card counts the
// catalog the availability grid already fetches. M2 extends this with per-kind
// counts and the largest `max_people`.
import type { Campsite } from '@/api/campsite-api';

export interface SiteCounts {
  total: number;
}

export function siteCountsOf(campsites: readonly Campsite[] | null | undefined): SiteCounts {
  return { total: campsites?.length ?? 0 };
}
