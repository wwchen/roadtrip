import { apiError } from '@/server/contractIds';
import { parsePoisRequest } from '@/server/poiRequest';
import { jsonBody } from '@/server/requestBody';
import { viewportPois } from '@/server/service/poiService';

export const dynamic = 'force-dynamic';

const STATUS_BAD_REQUEST = 400;
const BAD_REQUEST = 'bad_request';
const UNPARSEABLE_BODY = 'body is not JSON';

export async function POST(request: Request): Promise<Response> {
  const body = await jsonBody(request);
  const parsed = body === undefined ? { problem: BAD_REQUEST, detail: UNPARSEABLE_BODY } : parsePoisRequest(body);
  if ('problem' in parsed) return apiError(STATUS_BAD_REQUEST, BAD_REQUEST, parsed.detail);
  return Response.json(await viewportPois(parsed.bbox, parsed.zoom, parsed.categories));
}
