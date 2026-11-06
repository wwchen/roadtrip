import { apiError } from '@/server/contractIds';
import { jsonBody } from '@/server/requestBody';
import { parseOnRouteRequest } from '@/server/routeRequest';
import { CorridorUnavailableError, poisOnRoute, routePlanner, RoutingUnavailableError } from '@/server/service/routePlanService';

export const dynamic = 'force-dynamic';

const STATUS_BAD_REQUEST = 400;
const STATUS_UNAVAILABLE = 503;
const ROUTING_UNAVAILABLE = 'routing_unavailable';
const BAD_REQUEST = 'bad_request';
const UNPARSEABLE_BODY = 'body is not JSON';

export async function POST(request: Request): Promise<Response> {
  const planner = routePlanner();
  if (planner === null) return apiError(STATUS_UNAVAILABLE, ROUTING_UNAVAILABLE);
  const body = await jsonBody(request);
  if (body === undefined) return apiError(STATUS_BAD_REQUEST, BAD_REQUEST, UNPARSEABLE_BODY);
  const parsed = parseOnRouteRequest(body);
  if ('error' in parsed) return apiError(STATUS_BAD_REQUEST, parsed.error, parsed.detail);
  try {
    return Response.json(await poisOnRoute(planner, parsed.coords, parsed.radiusMiles, parsed.categories));
  } catch (error) {
    if (error instanceof RoutingUnavailableError || error instanceof CorridorUnavailableError) {
      console.warn('on-route lookup failed:', error.message);
      return apiError(STATUS_UNAVAILABLE, ROUTING_UNAVAILABLE);
    }
    throw error;
  }
}
