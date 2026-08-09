import { Button } from '@ui';
import {
  DirectionsButton,
  DrawerHeader,
  UpstreamTable,
  coordinatesOf,
  subline,
  text,
  useDistanceTo,
} from './parts';
import type { DrawerContentProps } from './registry';
import { amenityLabels, busyHours, rateRows } from './supercharger-detail';

/**
 * Tesla Supercharger drawer. Port of web/drawer/supercharger.js.
 *
 * Everything interesting about a site comes from Tesla's own capture, which the ETL
 * stores verbatim rather than promoting field by field — so the drawer reads
 * `upstream.detail` on top of `detailPayload`, and a new pill needs no backend
 * schema change. That merge order is the legacy one: the verbatim capture wins.
 */
export function SuperchargerDrawer({ feature, onClose }: DrawerContentProps) {
  const p = feature.properties;
  const [lng, lat] = coordinatesOf(feature);
  const distance = useDistanceTo(lng, lat);
  const name = text(p.name) || 'Supercharger';

  const detail = superchargerDetail(p);
  const address = [text(p.street), text(p.city), [text(p.state), text(p.postcode)].filter(Boolean).join(' ')]
    .filter(Boolean)
    .join(', ');
  // Tesla's "where in the parking lot" label ("East Victoria Park - Lot 335"), which
  // is often more useful for navigation than the city-level name. Suppressed when it
  // just repeats the name.
  const commonSite =
    text(detail.commonSiteName) && text(detail.commonSiteName) !== name
      ? text(detail.commonSiteName)
      : '';

  // Coords AND label, so the dropped pin lands on the charger and Google routes from
  // wherever the user is, on any platform. (The legacy URL also carried two dead
  // `query` params before this one; last-wins made them no-ops.)
  const mapsUrl =
    lng != null && lat != null
      ? `https://www.google.com/maps/search/?api=1&query=${encodeURIComponent(name)}%20${lat},${lng}`
      : '';

  const specs = [
    num(p.stallCount) ? `${num(p.stallCount)} stalls` : '',
    num(p.powerKilowatt) ? `${num(p.powerKilowatt)} kW` : '',
    num(p.v2) ? `V2×${num(p.v2)}` : '',
    num(p.v3) ? `V3×${num(p.v3)}` : '',
    num(p.v4) ? `V4×${num(p.v4)}` : '',
    num(p.nacs) ? `NACS×${num(p.nacs)}` : '',
    num(p.tpc) ? `TPC×${num(p.tpc)}` : '',
  ].filter(Boolean);

  // Capability flags, with the two non-obvious ones explained on hover.
  const features: Array<[string, string?]> = [];
  if ((detail.accessHours as { twentyFourSeven?: unknown } | undefined)?.twentyFourSeven) {
    features.push(['24/7']);
  }
  if (detail.openToNonTeslas) {
    features.push(['Magic Dock', 'NACS adapter built-in — works for non-Tesla EVs']);
  }
  if (detail.isTrailerFriendly) features.push(['Trailer-friendly', 'Pull-through stalls']);

  const amenities = amenityLabels(detail.amenities);
  const rates = rateRows(p.pricebooks);
  const busy = busyHours(detail.availabilityProfile, text(detail.timeZone) || undefined);

  return (
    <>
      <DrawerHeader name={name} sub={subline([address, distance])} />
      {commonSite ? <div className="rt-drawer-meta">{commonSite}</div> : null}

      <div className="rt-drawer-section rt-drawer-actions">
        <DirectionsButton name={name} lng={lng} lat={lat} kind="SC" onAdded={onClose} />
        {mapsUrl ? (
          <Button variant="primary" href={mapsUrl} target="_blank" rel="noopener">
            Open in Google Maps
          </Button>
        ) : null}
      </div>

      {specs.length > 0 || features.length > 0 ? (
        <div className="rt-drawer-pills">
          {specs.map((label) => (
            <span className="rt-drawer-pill" key={label}>
              {label}
            </span>
          ))}
          {features.map(([label, hint]) => (
            <span className="rt-drawer-pill" key={label} title={hint}>
              {label}
            </span>
          ))}
        </div>
      ) : null}

      {/* Amenities are their own row so "what's here" stays scannable without the
          hardware and capability flags mixed in. */}
      {amenities.length > 0 ? (
        <div className="rt-sc-amenities">
          <span className="rt-sc-label">Amenities</span>
          <span className="rt-drawer-pills">
            {amenities.map((label) => (
              <span className="rt-drawer-pill" key={label}>
                {label}
              </span>
            ))}
          </span>
        </div>
      ) : null}

      {busy ? (
        <div className="rt-sc-busy">
          <div className="rt-sc-busy-head">
            <span className="rt-sc-label">Today&rsquo;s busy hours</span>
            <span className="rt-drawer-meta">peak {busy.peakLabel}</span>
          </div>
          <div className="rt-sc-bars">
            {busy.bars.map((bar) => (
              <span
                key={bar.hour}
                className="rt-sc-bar"
                data-bucket={bar.bucket}
                data-now={bar.now ? '' : undefined}
                style={{ height: `${bar.height}px` }}
                title={bar.label}
              />
            ))}
          </div>
          <div className="rt-sc-axis">
            <span>12a</span>
            <span>6a</span>
            <span>12p</span>
            <span>6p</span>
            <span>12a</span>
          </div>
        </div>
      ) : null}

      {text(p.dateOpened) ? (
        <div className="rt-drawer-meta">Opened {text(p.dateOpened)}</div>
      ) : null}

      <div className="rt-drawer-section rt-sc-rates">
        {rates ? (
          rates.map((row) => (
            <div className={`rt-sc-rate rt-sc-rate--${row.kind}`} key={`${row.kind}-${row.label}`}>
              <span className="rt-sc-rate-label">
                {row.label}
                {row.currencyTag ? <span className="rt-drawer-meta"> {row.currencyTag}</span> : null}
              </span>
              <span className="rt-sc-rate-value">{row.rate}</span>
            </div>
          ))
        ) : (
          <div className="rt-drawer-meta">No pricing on file.</div>
        )}
      </div>

      <UpstreamTable upstream={p.upstream} />
    </>
  );
}

/**
 * The Tesla detail bag the pills read.
 *
 * `upstream.detail` is the verbatim capture and wins over the promoted
 * `detailPayload`; the two fields the flattener lifts out (`availabilityProfile`,
 * `timeZone`) are folded back in when the capture itself lacks them.
 */
function superchargerDetail(p: Record<string, unknown>): Record<string, unknown> {
  const upstream = p.upstream as { detail?: Record<string, unknown> } | undefined;
  const detail: Record<string, unknown> = {
    ...((p.detailPayload as Record<string, unknown>) ?? {}),
    ...(upstream?.detail ?? {}),
  };
  if (p.availabilityProfile && !detail.availabilityProfile) {
    detail.availabilityProfile = p.availabilityProfile;
  }
  if (p.timeZone && !detail.timeZone) detail.timeZone = p.timeZone;
  return detail;
}

/** A numeric property, or 0 — the pills are all "render when non-zero". */
const num = (value: unknown): number => {
  const n = Number(value);
  return Number.isFinite(n) ? n : 0;
};
