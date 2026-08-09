import { Button } from '@ui';
import { AvailabilityWeek } from '@/features/availability/AvailabilityWeek';
import { descriptionHtml, upstreamDecorations } from '@/lib/upstream-html';
import {
  amenityList,
  activityList,
  availabilitySupported,
  campgroundCtas,
  carrierSignals,
  hasDetails,
  isNoCta,
  parentParkName,
  rating,
  seasonVerdict,
  structuredDetails,
  verified,
  type DetailValue,
} from './campground-detail';
import {
  DirectionsButton,
  DrawerHeader,
  Pills,
  ProviderHtml,
  UpstreamTable,
  coordinatesOf,
  subline,
  text,
  useDistanceTo,
} from './parts';
import type { DrawerContentProps } from './registry';

/**
 * Campground drawer. Port of web/drawer/campground.js plus the card sections from
 * web/campground-card.js (RFC 0003 + 0007).
 *
 * Above-the-fold order is deliberate and carried over: name → containing park →
 * agency → region and distance → season verdict → availability → actions. On a phone
 * that is about 310px of headroom, so anything below "More details" is collapsed.
 *
 * The availability grid (`features/availability/AvailabilityWeek`) mounts below the
 * actions, gated on the backend's own provider-capability flag: "no availability
 * shown" and "this provider has no availability" are different facts, and only the
 * flag can tell them apart.
 *
 * Watch capture deliberately has no top-level button: it lives inside the grid's
 * cells and day panel, and the reserve CTA stays neutral ("View on recreation.gov")
 * because our availability is more permissive than the vendor's booking flow —
 * routing intent through watches avoids implying a guarantee we cannot keep.
 */
export function CampgroundDrawer({ feature, onClose }: DrawerContentProps) {
  const p = feature.properties;
  const [lng, lat] = coordinatesOf(feature);
  const distance = useDistanceTo(lng, lat);

  const name = text(p.name) || 'Campground';
  const decorations = upstreamDecorations(p.upstream);
  // Parent context precedence, as the original had it: the upstream record's own
  // parent, then a promoted field, then an inference from official-link titles.
  const parent =
    decorations.parentName || text(p.parent_name) || parentParkName(p) || text(p.typeLabel);
  const agency = text(p.agency).trim();
  const region = text(p.state) || text(p.country);

  const verdict = seasonVerdict(p.season, p.reservable);
  const ctas = campgroundCtas(p);
  const details = structuredDetails(p);
  const signals = carrierSignals(p);
  const stars = rating(p);
  const freshness = verified(p);
  const about = descriptionHtml(p.description);
  const photo = text(p.photo_url);
  const sites = Number(p.sites);

  return (
    <>
      {/* Flush against the drawer edges; the backend owns promoting a photo per source. */}
      {photo ? (
        <div
          className="rt-drawer-hero"
          role="img"
          aria-label={name}
          style={{ backgroundImage: `url('${encodeURI(photo)}')` }}
        />
      ) : null}

      <DrawerHeader
        name={name}
        sub={subline([region, distance])}
        above={
          <>
            {parent ? <div className="rt-cg-parent">{parent}</div> : null}
            {agency ? <div className="rt-cg-agency">{agency}</div> : null}
          </>
        }
        verdict={
          verdict ? (
            <span className={`rt-cg-verdict rt-cg-verdict--${verdict.tone}`}>{verdict.text}</span>
          ) : null
        }
      />

      <div className="rt-drawer-section rt-drawer-actions">
        <DirectionsButton name={name} lng={lng} lat={lat} kind="CG" onAdded={onClose} />
        {isNoCta(ctas) ? (
          <span className="rt-cg-cta-disabled">{ctas.disabledLabel}</span>
        ) : (
          ctas.map((cta) => (
            <Button
              key={cta.url}
              variant={cta.variant}
              href={cta.url}
              target="_blank"
              rel="noreferrer"
            >
              {cta.label}
            </Button>
          ))
        )}
      </div>

      {availabilitySupported(p) ? (
        // Gated on the backend's own provider-capability flag, so the grid only
        // appears for pins that genuinely have availability to show — rather than
        // rendering an empty week for a campground nobody can book through us.
        <div className="rt-drawer-section">
          <AvailabilityWeek feature={feature} />
        </div>
      ) : null}

      {about ? (
        <section className="rt-drawer-section">
          <h3>About</h3>
          <ProviderHtml html={about} />
        </section>
      ) : null}

      {decorations.feesHtml ? (
        <section className="rt-drawer-section">
          <h3>Fees &amp; cancellation</h3>
          <ProviderHtml html={decorations.feesHtml} />
        </section>
      ) : null}

      {decorations.stayLimit || decorations.directionsHtml ? (
        <section className="rt-drawer-section rt-cg-upstream-meta">
          {decorations.stayLimit ? (
            <div>
              <strong>Stay limit:</strong> {decorations.stayLimit}
            </div>
          ) : null}
          {decorations.directionsHtml ? (
            <div>
              <strong>Directions:</strong> <ProviderHtml html={decorations.directionsHtml} inline />
            </div>
          ) : null}
        </section>
      ) : null}

      {hasDetails(details) || signals.length > 0 || stars || freshness ? (
        // Desktop has the room, so it opens by default; a phone keeps it collapsed so
        // the verdict and CTAs stay above the fold.
        <details className="rt-drawer-section rt-cg-details" open={isDesktop()}>
          <summary>More details</summary>

          {details.groups.map((group) => (
            <section className="rt-cg-group" key={group.title}>
              <h4>{group.title}</h4>
              <div className="rt-cg-grid">
                {group.rows.map((row) => (
                  <div className="rt-cg-row" key={row.label}>
                    <span className="rt-cg-row-label">{row.label}</span>
                    <span className="rt-cg-row-value">
                      <DetailValueView value={row.value} />
                    </span>
                  </div>
                ))}
              </div>
            </section>
          ))}

          {details.links.length > 0 ? (
            <section className="rt-cg-group">
              <h4>Links</h4>
              <div className="rt-cg-links">
                {details.links.map((link) => (
                  <a key={link.href} href={link.href} target="_blank" rel="noreferrer">
                    {link.label}
                  </a>
                ))}
              </div>
            </section>
          ) : null}

          {details.alerts.length > 0 ? (
            <section className="rt-cg-group">
              <h4>Alerts</h4>
              <div className="rt-cg-alerts">
                {details.alerts.map((alert, index) => (
                  <div className="rt-cg-alert" key={`${alert.title}-${index}`}>
                    {alert.title ? <strong>{alert.title}</strong> : null}
                    {alert.body ? <span>{alert.body}</span> : null}
                  </div>
                ))}
              </div>
            </section>
          ) : null}

          <Pills items={[...amenityList(p), ...activityList(p)]} />

          {signals.length > 0 ? (
            <div className="rt-cg-signals">
              {signals.map((signal) => (
                <span
                  className="rt-cg-signal"
                  key={signal.carrier}
                  data-bucket={signal.bucket}
                  title={signal.count != null ? `${signal.count} reports` : undefined}
                >
                  <span className="rt-cg-signal-carrier">{signal.label}</span>
                  <span className="rt-cg-signal-value">{signal.avg.toFixed(1)}</span>
                </span>
              ))}
            </div>
          ) : null}

          {stars ? (
            <div className="rt-cg-rating">
              <span className="rt-cg-stars">{stars.stars}</span> {stars.average.toFixed(1)}
              <span className="rt-drawer-meta"> ({stars.count.toLocaleString()})</span>
            </div>
          ) : null}

          {Number.isFinite(sites) && sites > 0 ? (
            <div className="rt-cg-sites">{sites} sites</div>
          ) : null}

          {text(p.booking_system) ? (
            <div className="rt-drawer-meta">Booking via {text(p.booking_system)}</div>
          ) : null}

          {freshness ? (
            <div className={freshness.stale ? 'rt-cg-verified rt-cg-verified--stale' : 'rt-cg-verified'}>
              Verified {freshness.date}
              {freshness.stale ? ' · check before booking' : ''}
            </div>
          ) : null}
        </details>
      ) : null}

      <UpstreamTable upstream={p.upstream} />
    </>
  );
}

function DetailValueView({ value }: { value: DetailValue }) {
  if (value.kind === 'link') {
    return (
      <a href={value.href} target="_blank" rel="noreferrer">
        {value.label}
      </a>
    );
  }
  if (value.kind === 'chips') {
    return (
      <>
        {value.chips.map((chip) => (
          <span className="rt-cg-connection" key={chip.key}>
            <span>{chip.key}</span>
            <code>{chip.value}</code>
          </span>
        ))}
      </>
    );
  }
  return <>{value.text}</>;
}

/** Desktop gets the accordion open; matches the 768px breakpoint in drawer.css. */
const isDesktop = (): boolean => window.matchMedia?.('(min-width: 768px)').matches ?? false;
