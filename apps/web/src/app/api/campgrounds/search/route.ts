import { DEFAULT_SEARCH_BBOX, bboxToPolygonGeoJson } from '@/server/boundary';
import { InvalidBoundaryError, searchCampgrounds } from '@/server/service/campgroundService';

export const dynamic = 'force-dynamic';

const BBOX_PARAMETER = 'bbox';
const SERVER_TIMING_HEADER = 'Server-Timing';
const DB_METRIC = 'db';
const DURATION_DECIMALS = 1;
const STATUS_BAD_REQUEST = 400;
const INVALID_BBOX_MESSAGE = 'bbox must be west,south,east,north in degrees';

function badRequest(): Response {
  return Response.json({ error: INVALID_BBOX_MESSAGE }, { status: STATUS_BAD_REQUEST });
}

export async function GET(request: Request): Promise<Response> {
  const raw = new URL(request.url).searchParams.get(BBOX_PARAMETER) ?? DEFAULT_SEARCH_BBOX;
  const boundary = bboxToPolygonGeoJson(raw);
  if (boundary === null) return badRequest();

  const started = performance.now();
  let result;
  try {
    result = await searchCampgrounds(boundary);
  } catch (error) {
    if (error instanceof InvalidBoundaryError) return badRequest();
    throw error;
  }
  const elapsed = performance.now() - started;

  return Response.json(result, {
    headers: {
      [SERVER_TIMING_HEADER]: `${DB_METRIC};dur=${elapsed.toFixed(DURATION_DECIMALS)}`,
    },
  });
}
