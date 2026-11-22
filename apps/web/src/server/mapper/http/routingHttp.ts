import type { RouteErrorDto } from '@/server/contract';
import type { RoutingFailure } from '@/server/service/routing/model';
import { STATUS_BAD_GATEWAY, STATUS_SERVICE_UNAVAILABLE, STATUS_UNPROCESSABLE } from './httpAnswer';

export interface RoutingFailureAnswer {
  status: number;
  body: RouteErrorDto;
}

function codeAndStatus(failure: RoutingFailure): { error: string; status: number } {
  switch (failure) {
    case 'not_configured':
      return { error: 'routing_not_configured', status: STATUS_SERVICE_UNAVAILABLE };
    case 'unreachable':
      return { error: 'routing_unreachable', status: STATUS_BAD_GATEWAY };
    case 'upstream_auth':
      return { error: 'routing_auth_failed', status: STATUS_BAD_GATEWAY };
    case 'upstream_rate_limited':
      return { error: 'routing_rate_limited', status: STATUS_SERVICE_UNAVAILABLE };
    case 'upstream_error':
      return { error: 'routing_unavailable', status: STATUS_BAD_GATEWAY };
    case 'no_route':
      return { error: 'route_not_found', status: STATUS_UNPROCESSABLE };
    case 'no_segment':
      return { error: 'route_no_segment', status: STATUS_UNPROCESSABLE };
    case 'invalid_input':
      return { error: 'route_invalid_input', status: STATUS_UNPROCESSABLE };
    case 'corridor':
      return { error: 'corridor_unavailable', status: STATUS_SERVICE_UNAVAILABLE };
  }
}

export function routingFailureAnswer(failure: RoutingFailure, detail: string): RoutingFailureAnswer {
  const { error, status } = codeAndStatus(failure);
  return { status, body: { error, detail } };
}
