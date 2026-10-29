import { DEFAULT_SEARCH_BBOX, parseBbox, type BboxProblem } from './boundary';
import type { CampgroundSearchDto } from './dto';
import { InvalidBoundaryError, searchCampgrounds } from './service/campgroundService';

export const BBOX_PARAMETER = 'bbox';

export const BBOX_PROBLEM_MESSAGES: Record<BboxProblem, string> = {
  malformed: `bbox must be west,south,east,north in degrees, for example ${DEFAULT_SEARCH_BBOX}`,
  'crosses-antimeridian':
    'a bbox crossing the antimeridian (west greater than east) is not supported',
};

export type BboxSearchOutcome =
  | { status: 'found'; result: CampgroundSearchDto; dbMillis: number }
  | { status: 'rejected'; problem: BboxProblem };

export async function searchByBbox(raw: string | undefined): Promise<BboxSearchOutcome> {
  const parsed = parseBbox(raw ?? DEFAULT_SEARCH_BBOX);
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
