import type { RouteErrorDto } from '@/server/contract';
import { parseRouteQuery } from '@/server/routeRequest';
import { routePlanner } from '@/server/service/routePlanService';

export const dynamic = 'force-dynamic';

const STATUS_BAD_REQUEST = 400;
const STATUS_UNAVAILABLE = 503;
const ROUTING_UNAVAILABLE = 'routing_unavailable';
const TOKEN_NOT_SET = 'MAPBOX_TOKEN not set';

const routeError = (error: string, detail: string, status: number) => {
  const body: RouteErrorDto = { error, detail };
  return Response.json(body, { status });
};

export async function GET(request: Request): Promise<Response> {
  const planner = routePlanner();
  if (planner === null) return routeError(ROUTING_UNAVAILABLE, TOKEN_NOT_SET, STATUS_UNAVAILABLE);
  const query = parseRouteQuery(new URL(request.url).searchParams);
  if ('error' in query) return routeError(query.error, query.detail, STATUS_BAD_REQUEST);
  const result = await planner.plan(query.coords, query.radiusMiles);
  switch (result.status) {
    case 'planned':
      return Response.json(result.body);
    case 'duplicate_adjacent':
      return routeError(result.status, `points ${result.index} and ${result.index - 1} are identical`, STATUS_BAD_REQUEST);
    default:
      return routeError(result.status, result.detail, STATUS_UNAVAILABLE);
  }
}
