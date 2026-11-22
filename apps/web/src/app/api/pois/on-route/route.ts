import { apiError } from '@/server/contractIds';
import { routingFailureAnswer } from '@/server/mapper/http/routingHttp';
import { jsonBody } from '@/server/requestBody';
import { parseOnRouteRequest } from '@/server/routeRequest';
import { CorridorUnavailableError, poisOnRoute, routePlanner, RoutingUnavailableError } from '@/server/service/routePlanService';

export const dynamic = 'force-dynamic';

const STATUS_BAD_REQUEST = 400;
const BAD_REQUEST = 'bad_request';
const UNPARSEABLE_BODY = 'body is not JSON';
const TOKEN_NOT_SET = 'MAPBOX_TOKEN not set';

const answered = ({ status, body }: ReturnType<typeof routingFailureAnswer>) => Response.json(body, { status });

export async function POST(request: Request): Promise<Response> {
  const planner = routePlanner();
  if (planner === null) return answered(routingFailureAnswer('not_configured', TOKEN_NOT_SET));
  const body = await jsonBody(request);
  if (body === undefined) return apiError(STATUS_BAD_REQUEST, BAD_REQUEST, UNPARSEABLE_BODY);
  const parsed = parseOnRouteRequest(body);
  if ('error' in parsed) return apiError(STATUS_BAD_REQUEST, parsed.error, parsed.detail);
  try {
    return Response.json(await poisOnRoute(planner, parsed.coords, parsed.radiusMiles, parsed.categories));
  } catch (error) {
    if (error instanceof RoutingUnavailableError) {
      console.warn('on-route directions failed:', error);
      return answered(routingFailureAnswer(error.failure, error.message));
    }
    if (error instanceof CorridorUnavailableError) {
      console.warn('on-route corridor failed:', error);
      return answered(routingFailureAnswer('corridor', error.message));
    }
    throw error;
  }
}
