// Cell coverage, as one bar per carrier.
//
// Neutral at every strength, deliberately: a weak signal is information, not an
// error, and painting it red would put it in the same vocabulary as "closed". The
// bucket drives length, not hue.
import type { CarrierSignal } from '../campground-detail';

export function CarrierSignals({ signals }: { signals: CarrierSignal[] }) {
  return (
    <ul className="rt-poi-signals">
      {signals.map((signal) => (
        <li
          className="rt-poi-signal"
          key={signal.carrier}
          title={signal.count != null ? `${signal.count} reports` : undefined}
        >
          <span className="rt-poi-signal-carrier">{signal.label}</span>
          <span className="rt-poi-signal-bar" data-bucket={signal.bucket} aria-hidden="true" />
          <span className="rt-poi-signal-value">{signal.avg.toFixed(1)}</span>
        </li>
      ))}
    </ul>
  );
}
