// The data shapes the POI blocks take.
//
// Deliberately narrow and provider-agnostic: a type component's job is to turn
// whatever a provider shipped into these, and the blocks' job is to render them.
// Nothing here knows what recreation.gov or Tesla call anything.
import type { ReactNode } from 'react';

/** A step in the ancestry trail: state → park → this place. */
export interface PoiCrumb {
  label: string;
  /** Absent on the last crumb, which is the page you are already on. */
  href?: string;
}

/** A labelled fact in the type's one spec list — "Stalls · 51 · up to 250 kW". */
export interface PoiSpec {
  label: string;
  value: ReactNode;
}

/**
 * The type's one spec block.
 *
 * Every type gets at most one, and its heading is what the type is *for*: stalls
 * and price for a charger, the morning release for a first-come ground, the trail
 * stats for a trailhead, stay details for a reservable campground.
 */
export interface PoiSpecList {
  heading: string;
  rows: PoiSpec[];
}

/** An outbound link in the links block. */
export interface PoiLink {
  label: string;
  href: string;
}

/** A neighbour in the "nearby" carousel. */
export interface PoiNeighbour {
  id: string;
  name: string;
  /** "2 mi · State park". */
  meta: string;
  /** "7 nights open" / "Day use only" — the reason to tap through. */
  status?: string;
  href?: string;
}

/** How fresh the record is, and whether that is old enough to warn about. */
export interface PoiVerified {
  date: string;
  stale: boolean;
}
