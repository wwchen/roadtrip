// The line under the topbar's buttons.
//
// The vanilla had one `#tb-status` element whose `innerHTML` was written by four
// callers — a "Computing route…" message, a leg breakdown, a routing error, and a
// geolocation failure — and whose only distinction between them was a CSS class.
// Four states, one node, and the last writer won: a routing error could be
// overwritten by a leg table from a previous route, and there was no way to show
// both a leg breakdown and a location failure.
//
// Here each is its own element with its own rules, and precedence is explicit.
import type { RouteLegLine } from './route-summary';

export interface RouteStatusProps {
  computing: boolean;
  /**
   * The message to show, already chosen by the caller.
   *
   * One string rather than one prop per source (routing, geolocation, a bad shared
   * link): the precedence between them is a product decision and it belongs in one
   * place — `TopBar` — instead of being re-derived here from three nullable props.
   */
  error: string | null;
  /** Per-leg lines, empty unless the trip has three or more stops. */
  legs: readonly RouteLegLine[];
}

export function RouteStatus({ computing, error, legs }: RouteStatusProps) {
  // An error outranks progress: a failed request is not still computing, and the
  // vanilla's shared node made that ordering implicit in call order.
  const message = error;
  if (!message && !computing && legs.length === 0) return null;

  return (
    <div className={`tb-status visible${error ? ' error' : ''}`} role="status">
      {message ? (
        message
      ) : computing ? (
        'Computing route…'
      ) : (
        <div className="tb-legs">
          {legs.map((leg) => (
            <div key={`${leg.from}-${leg.to}`}>
              {leg.from} → {leg.to}: {leg.distance} · {leg.duration}
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
