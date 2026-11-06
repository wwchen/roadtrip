import {
  DEFAULT_SEARCH_BBOX,
  MAX_BBOX_AREA_SQUARE_DEGREES,
  parseBbox,
  type BboxProblem,
} from './boundary';
import type { CampgroundSearchDto } from './dto';
import { InvalidBoundaryError, searchCampgrounds } from './service/campgroundService';

export const BBOX_PARAMETER = 'bbox';

export const BBOX_PROBLEM_MESSAGES: Record<BboxProblem, string> = {
  malformed: `bbox must be west,south,east,north in degrees, for example ${DEFAULT_SEARCH_BBOX}`,
  'crosses-antimeridian':
    'a bbox crossing the antimeridian (west greater than east) is not supported',
  'inverted-latitudes': 'a bbox with south greater than north is not supported',
  'too-large': `a bbox may cover at most ${MAX_BBOX_AREA_SQUARE_DEGREES} square degrees (about 6 by 6 degrees); zoom in`,
};

export type BboxInput = string | readonly string[] | undefined;

export function bboxInputFrom(values: readonly string[]): BboxInput {
  return values.length > 1 ? values : values[0];
}

export type BboxSearchOutcome =
  | { status: 'found'; result: CampgroundSearchDto; dbMillis: number }
  | { status: 'rejected'; problem: BboxProblem };

export async function searchByBbox(input: BboxInput): Promise<BboxSearchOutcome> {
  if (typeof input !== 'string' && input !== undefined) {
    return { status: 'rejected', problem: 'malformed' };
  }
  const parsed = parseBbox(input ?? DEFAULT_SEARCH_BBOX);
  if ('problem' in parsed) return { status: 'rejected', problem: parsed.problem };

  const started = performance.now();
  try {
    const result = await searchCampgrounds(parsed.polygon);
    return { status: 'found', result, dbMillis: performance.now() - started };
  } catch (error) {
    if (error instanceof InvalidBoundaryError) return { status: 'rejected', problem: 'malformed' };
    throw error;
  }
}
