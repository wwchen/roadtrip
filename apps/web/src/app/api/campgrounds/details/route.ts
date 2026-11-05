import { parseDetailsRequest } from '@/server/campgroundSearchRequest';
import { apiError } from '@/server/contractIds';
import { jsonBody } from '@/server/requestBody';
import { getCampgroundDetails } from '@/server/service/campgroundSearchService';

export const dynamic = 'force-dynamic';

const STATUS_BAD_REQUEST = 400;
const BAD_REQUEST = 'bad_request';
const UNPARSEABLE_BODY = 'body is not JSON';

export async function POST(request: Request): Promise<Response> {
  const body = await jsonBody(request);
  if (body === undefined) return apiError(STATUS_BAD_REQUEST, BAD_REQUEST, UNPARSEABLE_BODY);
  const parsed = parseDetailsRequest(body);
  if ('problem' in parsed) return apiError(STATUS_BAD_REQUEST, parsed.problem, parsed.detail);
  return Response.json(await getCampgroundDetails(parsed.ids));
}
