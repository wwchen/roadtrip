import { parseCampgroundSearchRequest } from '@/server/campgroundSearchRequest';
import { apiError } from '@/server/contractIds';
import { jsonBody } from '@/server/requestBody';
import { InvalidBoundaryError } from '@/server/service/campgroundService';
import { searchCampgroundsByContract } from '@/server/service/campgroundSearchService';
import {
  BBOX_PARAMETER,
  BBOX_PROBLEM_MESSAGES,
  bboxInputFrom,
  searchByBbox,
} from '@/server/bboxSearch';

export const dynamic = 'force-dynamic';

const SERVER_TIMING_HEADER = 'Server-Timing';
const DB_METRIC = 'db';
const DURATION_DECIMALS = 1;
const STATUS_BAD_REQUEST = 400;
const BAD_REQUEST = 'bad_request';
const BAD_BOUNDARY = 'bad_boundary';
const UNPARSEABLE_BODY = 'body is not JSON';
const INVALID_BOUNDARY = 'boundary is not valid GeoJSON geometry';

export async function GET(request: Request): Promise<Response> {
  const supplied = new URL(request.url).searchParams.getAll(BBOX_PARAMETER);
  const outcome = await searchByBbox(bboxInputFrom(supplied));
  if (outcome.status === 'rejected') {
    return Response.json(
      { error: BBOX_PROBLEM_MESSAGES[outcome.problem] },
      { status: STATUS_BAD_REQUEST },
    );
  }

  return Response.json(outcome.result, {
    headers: {
      [SERVER_TIMING_HEADER]: `${DB_METRIC};dur=${outcome.dbMillis.toFixed(DURATION_DECIMALS)}`,
    },
  });
}

export async function POST(request: Request): Promise<Response> {
  const body = await jsonBody(request);
  if (body === undefined) return apiError(STATUS_BAD_REQUEST, BAD_REQUEST, UNPARSEABLE_BODY);
  const parsed = parseCampgroundSearchRequest(body);
  if ('problem' in parsed) return apiError(STATUS_BAD_REQUEST, parsed.problem, parsed.detail);
  try {
    return Response.json(await searchCampgroundsByContract(parsed));
  } catch (error) {
    if (error instanceof InvalidBoundaryError) return apiError(STATUS_BAD_REQUEST, BAD_BOUNDARY, INVALID_BOUNDARY);
    throw error;
  }
}
