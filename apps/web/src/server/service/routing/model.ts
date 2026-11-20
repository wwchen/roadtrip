export type RoutingFailure =
  | 'not_configured'
  | 'unreachable'
  | 'upstream_auth'
  | 'upstream_rate_limited'
  | 'upstream_error'
  | 'no_route'
  | 'no_segment'
  | 'invalid_input'
  | 'corridor';
