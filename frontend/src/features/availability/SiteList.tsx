// "Available sites" for the selected day.
//
// Port of web/availability/site-list.js. Renders nothing at all when the selected day
// has no openings: the day-detail panel owns that case, and an empty accordion header
// reading "Available sites (0)" is a worse answer than no accordion.
//
// Each row links straight into the provider's booking flow for that exact night,
// which is only possible because a day is selected — the same row in the matrix
// cannot, which is why the matrix arms a cell before opening it.
import type { Campsite, PoiCampsitesResponse } from '@/api/campsite-api';
import { availableCampsiteIds, availableCount } from '@/lib/day-fields';
import type { FusedDay } from './fuse';
import { siteName } from './matrix-rows';
import {
  campsitesForIds,
  compareListRows,
  rowDetails,
  siteListLabel,
} from './site-list-rows';
import {
  hasReservationUrlTemplate,
  reservationUrlFromTemplate,
  type ReservationUrlTemplates,
} from './booking-links';

export interface SiteListProps {
  state: 'loading' | 'success' | 'error';
  campsites: readonly Campsite[];
  reservationUrlTemplates: PoiCampsitesResponse['reservation_url_templates'] | undefined;
  error: string | null;
  expanded: boolean;
  onToggle: () => void;
  onRetry: () => void;
  /** The selected day, only when it actually has openings. */
  selectedDay: FusedDay | null;
  /** Exclusive end of the one-night stay being booked. */
  selectedEndDate: string | null;
}

export function SiteList({
  state,
  campsites,
  reservationUrlTemplates,
  error,
  expanded,
  onToggle,
  onRetry,
  selectedDay,
  selectedEndDate,
}: SiteListProps) {
  const availableIds = availableCampsiteIds(selectedDay);
  if (availableIds.length === 0) return null;

  const count = availableCount(selectedDay);
  const total = campsites.length > 0 ? campsites.length : null;

  if (state === 'loading') {
    return (
      <Section header={<Header label={siteListLabel(count, total)} expanded={false} disabled />}>
        <div className="cg-sites-skeleton" aria-busy="true">
          Loading sites…
        </div>
      </Section>
    );
  }

  if (state === 'error') {
    return (
      <Section header={<Header label={siteListLabel(count, total)} expanded={false} disabled />}>
        <div className="cg-sites-error">
          {error || "Couldn't load sites"} ·{' '}
          <button type="button" className="cg-sites-retry cg-link-button" onClick={onRetry}>
            Retry
          </button>
        </div>
      </Section>
    );
  }

  const rows = [...campsitesForIds(campsites, availableIds)].sort(compareListRows);

  return (
    <Section
      header={
        <Header label={siteListLabel(count, total)} expanded={expanded} onToggle={onToggle} />
      }
    >
      {expanded ? (
        rows.length === 0 ? (
          <div className="cg-sites-empty">No available sites for this date.</div>
        ) : (
          <ol className="cg-sites-rows">
            {rows.map((row) => (
              <Row
                key={String(row.id)}
                row={row}
                startDate={selectedDay?.date ?? null}
                endDate={selectedEndDate}
                reservationUrlTemplates={reservationUrlTemplates}
              />
            ))}
          </ol>
        )
      ) : null}
    </Section>
  );
}

function Section({ header, children }: { header: React.ReactNode; children: React.ReactNode }) {
  return (
    <section className="cg-sites">
      {header}
      {children}
    </section>
  );
}

function Header({
  label,
  expanded,
  disabled = false,
  onToggle,
}: {
  label: string;
  expanded: boolean;
  disabled?: boolean;
  onToggle?: () => void;
}) {
  return (
    <button
      type="button"
      className="cg-sites-toggle"
      aria-expanded={expanded}
      disabled={disabled}
      onClick={onToggle}
    >
      <span className="cg-sites-label">{label}</span>
      <span className="cg-sites-chevron" aria-hidden="true">
        {expanded ? '▾' : '▸'}
      </span>
    </button>
  );
}

function Row({
  row,
  startDate,
  endDate,
  reservationUrlTemplates,
}: {
  row: Partial<Campsite>;
  startDate: string | null;
  endDate: string | null;
  reservationUrlTemplates: ReservationUrlTemplates;
}) {
  const name = siteName(row);
  const details = rowDetails(row);
  const url = reservationUrlFromTemplate(row, {
    startDate,
    endDate,
    reservationUrlTemplates,
  });
  // The badge appears when this provider *has* a deep link, even if we could not
  // build one for these dates — so a row does not look unbookable because of a
  // missing selection.
  const bookable = Boolean(url) || hasReservationUrlTemplate(row, reservationUrlTemplates);

  const inner = (
    <>
      <div className="cg-sites-row-main">
        <div className="cg-sites-row-name">{name}</div>
        {row.loop_name ? <div className="cg-sites-row-loop">{String(row.loop_name)}</div> : null}
        {details.length > 0 ? (
          <div className="cg-sites-row-details">{details.join(' · ')}</div>
        ) : null}
      </div>
      {row.kind || bookable ? (
        <div className="cg-sites-row-side">
          {row.kind ? <span className="cg-sites-row-type">{String(row.kind)}</span> : null}
          {bookable ? <span className="cg-sites-row-book">Book</span> : null}
        </div>
      ) : null}
    </>
  );

  return (
    <li className="cg-sites-row" data-campsite-id={String(row.id ?? '')}>
      {url ? (
        <a
          className="cg-sites-row-link"
          href={url}
          target="_blank"
          rel="noreferrer"
          aria-label={`Book site ${name}`}
        >
          {inner}
        </a>
      ) : (
        inner
      )}
    </li>
  );
}
