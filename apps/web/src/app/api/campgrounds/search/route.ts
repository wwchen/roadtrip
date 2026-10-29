import { BBOX_PARAMETER, BBOX_PROBLEM_MESSAGES, searchByBbox } from '@/server/bboxSearch';

export const dynamic = 'force-dynamic';

const SERVER_TIMING_HEADER = 'Server-Timing';
const DB_METRIC = 'db';
const DURATION_DECIMALS = 1;
const STATUS_BAD_REQUEST = 400;

export async function GET(request: Request): Promise<Response> {
  const raw = new URL(request.url).searchParams.get(BBOX_PARAMETER) ?? undefined;
  const outcome = await searchByBbox(raw);
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
