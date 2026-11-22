import type { RouteErrorDto } from '@/server/contract';
import { routingFailureAnswer } from '@/server/mapper/http/routingHttp';
import { parseRouteQuery } from '@/server/routeRequest';
import { routePlanner } from '@/server/service/routePlanService';

export const dynamic = 'force-dynamic';

const STATUS_BAD_REQUEST = 400;
const TOKEN_NOT_SET = 'MAPBOX_TOKEN not set';

const answered = ({ status, body }: { status: number; body: RouteErrorDto }) => Response.json(body, { status });

const routeError = (error: string, detail: string, status: number) => {
  const body: RouteErrorDto = { error, detail };
  return Response.json(body, { status });
};

export async function GET(request: Request): Promise<Response> {
  const planner = routePlanner();
  if (planner === null) return answered(routingFailureAnswer('not_configured', TOKEN_NOT_SET));
  const query = parseRouteQuery(new URL(request.url).searchParams);
  if ('error' in query) return routeError(query.error, query.detail, STATUS_BAD_REQUEST);
  const result = await planner.plan(query.coords, query.radiusMiles);
  switch (result.status) {
    case 'planned':
      return Response.json(result.body);
    case 'duplicate_adjacent':
      return routeError(result.status, `points ${result.index} and ${result.index - 1} are identical`, STATUS_BAD_REQUEST);
    case 'routing_unavailable':
      console.warn('directions failed:', result.failure, result.detail);
      return answered(routingFailureAnswer(result.failure, result.detail));
    case 'corridor_unavailable':
      console.warn('corridor failed:', result.detail);
      return answered(routingFailureAnswer('corridor', result.detail));
  }
}
