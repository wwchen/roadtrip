// The charger page.
//
// Two things make it a different page from a campground, and both are omissions
// rather than rearrangements: there is no nightly grid and no stay details. Busy
// hours takes the availability slot, because "when is it full" is the same question
// the grid answers for a campground, asked at the resolution a charger has.
import { Button } from '@ui';
import {
  DirectionsButton,
  SharePoiButton,
  UpstreamTable,
  coordinatesOf,
  subline,
  text,
  useDistanceTo,
} from '../fields';
import { amenityLabels, busyHours, rateRows, type RateRow } from '../supercharger-detail';
import {
  PoiActions,
  PoiBlockHeading,
  PoiGlance,
  PoiIdentity,
  PoiProvenance,
  PoiSpecs,
  type PoiTag,
} from '../PoiBlocks';
import { PoiPageShell, type PoiBlockSlots } from '../PoiPageShell';
import { presentSpecs, type PoiTypeProps } from './common';

/** The heading the charger's one spec block carries. */
const CHARGING_HEADING = 'Charging';

/** Hour ticks under the busy-hours bars. */
const BUSY_AXIS = ['12a', '6a', '12p', '6p', '12a'] as const;

export function ChargerPoiPage({ feature, variant, onClose }: PoiTypeProps) {
  const p = feature.properties;
  const [lng, lat] = coordinatesOf(feature);
  const distance = useDistanceTo(lng, lat);
  const name = text(p.name) || 'Supercharger';

  const upstreamDetail = (p.upstream as { detail?: Record<string, unknown> } | undefined)
    ?.detail;
  const address = [
    text(p.street),
    text(p.city),
    [text(p.state), text(p.postcode)].filter(Boolean).join(' '),
  ]
    .filter(Boolean)
    .join(', ');
  // Tesla's "where in the parking lot" label ("East Victoria Park - Lot 335"), which
  // is often more useful for navigation than the city-level name. Suppressed when it
  // just repeats the name.
  const siteLabel = text(upstreamDetail?.commonSiteName);
  const commonSite = siteLabel && siteLabel !== name ? siteLabel : '';

  // Coords AND label, so the dropped pin lands on the charger and Google routes from
  // wherever the user is, on any platform.
  const mapsUrl =
    lng != null && lat != null
      ? `https://www.google.com/maps/search/?api=1&query=${encodeURIComponent(name)}%20${lat},${lng}`
      : '';

  const stalls = num(p.stall_count);
  const power = num(p.power_kilowatt);

  const openAllHours = Boolean(p.twenty_four_seven);
  const access = [openAllHours ? '24/7' : '', p.open_to_non_teslas ? 'Magic Dock' : '']
    .filter(Boolean)
    .join(' · ');

  const rates = rateRows(p.pricebooks);
  const busy = busyHours(p.availability_profile, text(p.time_zone) || undefined);

  const specs = presentSpecs([
    stalls
      ? { label: 'Stalls', value: power ? `${stalls} · up to ${power} kW` : String(stalls) }
      : null,
    ...(rates ?? []).map((row: RateRow) => ({
      label: row.label,
      value: (
        <>
          {row.rate}
          {row.currencyTag ? <span className="rt-poi-faint"> {row.currencyTag}</span> : null}
        </>
      ),
    })),
    access ? { label: 'Access', value: access } : null,
  ]);

  const tags: PoiTag[] = [
    ...(p.trailer_friendly ? [{ label: 'Trailer-friendly' }] : []),
    ...amenityLabels(p.amenities).map((label) => ({ label })),
  ];

  const blocks: PoiBlockSlots = {
    identity: (
      <PoiIdentity
        eyebrow="Charger · Tesla"
        title={name}
        subtitle={subline([address, commonSite, distance])}
      />
    ),
    actions: (
      <PoiActions>
        <DirectionsButton name={name} lng={lng} lat={lat} kind="SC" onAdded={onClose ?? noop} />
        {mapsUrl ? (
          <Button variant="primary" href={mapsUrl} target="_blank" rel="noopener">
            Open in Google Maps
          </Button>
        ) : null}
        <SharePoiButton id={feature.id} />
      </PoiActions>
    ),
    ...(busy
      ? {
          availability: (
            <section className="rt-poi-block">
              <div className="rt-poi-block-head">
                <PoiBlockHeading>Today&rsquo;s busy hours</PoiBlockHeading>
                <span className="rt-poi-block-meta">peak {busy.peakLabel}</span>
              </div>
              <div className="rt-poi-bars">
                {busy.bars.map((bar) => (
                  <span
                    key={bar.hour}
                    className="rt-poi-bar"
                    data-bucket={bar.bucket}
                    data-now={bar.now ? '' : undefined}
                    style={{ height: `${bar.height}px` }}
                    title={bar.label}
                  />
                ))}
              </div>
              <div className="rt-poi-axis">
                {BUSY_AXIS.map((tick, index) => (
                  <span key={`${tick}-${index}`}>{tick}</span>
                ))}
              </div>
            </section>
          ),
        }
      : null),
    ...(tags.length > 0 ? { glance: <PoiGlance tags={tags} /> } : null),
    ...(specs.length > 0
      ? { specs: <PoiSpecs list={{ heading: CHARGING_HEADING, rows: specs }} /> }
      : null),
    ...(p.upstream
      ? {
          provenance: (
            <PoiProvenance>
              <UpstreamTable upstream={p.upstream} />
            </PoiProvenance>
          ),
        }
      : null),
  };

  return <PoiPageShell variant={variant} blocks={blocks} />;
}

/** A numeric property, or 0 — every hardware spec is "render when non-zero". */
const num = (value: unknown): number => {
  const n = Number(value);
  return Number.isFinite(n) ? n : 0;
};

const noop = () => {};
