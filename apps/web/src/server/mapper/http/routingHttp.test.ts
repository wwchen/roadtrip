import { describe, expect, test } from 'vitest';
import { routingFailureAnswer } from './routingHttp';

describe('routingFailureAnswer', () => {
  test.each([
    ['not_configured', 'routing_not_configured', 503],
    ['unreachable', 'routing_unreachable', 502],
    ['upstream_auth', 'routing_auth_failed', 502],
    ['upstream_rate_limited', 'routing_rate_limited', 503],
    ['upstream_error', 'routing_unavailable', 502],
    ['no_route', 'route_not_found', 422],
    ['no_segment', 'route_no_segment', 422],
    ['invalid_input', 'route_invalid_input', 422],
    ['corridor', 'corridor_unavailable', 503],
  ] as const)('%s answers %s with %i and keeps the detail', (failure, error, status) => {
    expect(routingFailureAnswer(failure, 'the raw cause')).toEqual({ status, body: { error, detail: 'the raw cause' } });
  });
});
