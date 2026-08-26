// The availability table — "which nights are open", one row per place.
//
// This is the block that justifies the routed page. A 520px drawer can show one
// campground's week; it cannot show a park's twelve campgrounds against the same
// twelve nights, and that comparison is the whole question a group page answers.
// So the table lives here, in the domain, and a park page, a state page and the
// national-parks group page all fill their `availability` slot with it.
//
// Presentation only. It takes rows and nights as props and renders them; it owns
// no query, no week controller and no provider knowledge. Where the data comes
// from is the caller's problem, which is what lets the same component serve three
// pages that will fetch it three different ways.
//
// Colour never carries a state on its own: every cell prints a glyph, so the
// difference between "open" and "reserved" survives a colour-blind reader, a
// greyscale print and a low-contrast screen.
import { availabilityStatusMeta, type AvailabilityStatus } from '@/lib/availability-status';
import { PoiBlockHeading } from './PoiBlocks';
import './poi-page.css';

/**
 * One (place, night) cell.
 *
 * A union over the backend's own `AvailabilityStatus` rather than a bag of
 * booleans: "open" and "how many sites" are one fact, and only the open case has
 * a count to carry. Deriving the closed half with `Exclude` keeps the two in step
 * — a status added upstream arrives here without an edit, and cannot arrive
 * carrying a count it has no meaning for.
 */
export type AvailabilityCell =
  | { readonly status: 'available'; readonly open?: number }
  | { readonly status: Exclude<AvailabilityStatus, 'available'> };

/** One column: the night itself, and how its header reads. */
export interface AvailabilityNight {
  /** Stable key and the key cells are looked up by — an ISO date in practice. */
  readonly date: string;
  /** The header's first line, e.g. `Fri`. */
  readonly label: string;
  /** The header's second line, e.g. `Jul 4`. Optional; omitted lines close up. */
  readonly sublabel?: string;
}

/** One row: a place, and its cell for each night. */
export interface AvailabilityPlace {
  readonly id: string;
  readonly name: string;
  /** A second line under the name — loop, agency, distance, whatever the page has. */
  readonly note?: string;
  /** The place's own page. Absent means the row's name is text, not a dead link. */
  readonly href?: string;
  /**
   * Cells by night key. A `Map`, not an object literal, because the keys come from
   * data: a plain-object lookup would resolve `Object.prototype` members. A night
   * with no entry renders as `unknown` rather than as a blank, so a partial row
   * reads as "we do not know" instead of as "nothing there".
   */
  readonly cells: ReadonlyMap<string, AvailabilityCell>;
}

export interface AvailabilityTableProps {
  /** The block heading. */
  heading?: string;
  /** One line under the heading — what the window is, or where the numbers came from. */
  caption?: string;
  readonly nights: readonly AvailabilityNight[];
  readonly places: readonly AvailabilityPlace[];
  /** Shown instead of the table when there is nothing to compare yet. */
  emptyLabel?: string;
}

const DEFAULT_HEADING = 'Open nights';
const DEFAULT_EMPTY_LABEL = 'No nights to show yet.';
const UNKNOWN_CELL: AvailabilityCell = { status: 'unknown' };

/** The column header for the row names — a `<th>` needs a word, not a blank. */
const PLACE_COLUMN_LABEL = 'Place';

export function AvailabilityTable({
  heading = DEFAULT_HEADING,
  caption,
  nights,
  places,
  emptyLabel = DEFAULT_EMPTY_LABEL,
}: AvailabilityTableProps) {
  const empty = nights.length === 0 || places.length === 0;

  return (
    <section className="rt-poi-avail">
      <PoiBlockHeading>{heading}</PoiBlockHeading>
      {caption ? <p className="rt-poi-avail-caption">{caption}</p> : null}

      {empty ? (
        <p className="rt-poi-avail-empty">{emptyLabel}</p>
      ) : (
        <>
          {/* The scroll lives here and nowhere else: twelve nights overflow 520px,
              and a table that widens its own page makes the whole body scroll
              sideways. */}
          <div className="rt-poi-avail-scroll">
            <table className="rt-poi-avail-table">
              <thead>
                <tr>
                  <th scope="col" className="rt-poi-avail-place-head">
                    {PLACE_COLUMN_LABEL}
                  </th>
                  {nights.map((night) => (
                    <th scope="col" key={night.date} className="rt-poi-avail-night-head">
                      <span className="rt-poi-avail-night-label">{night.label}</span>
                      {night.sublabel ? (
                        <span className="rt-poi-avail-night-sublabel">{night.sublabel}</span>
                      ) : null}
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {places.map((place) => (
                  <tr key={place.id}>
                    <th scope="row" className="rt-poi-avail-place">
                      {place.href ? (
                        <a href={place.href}>{place.name}</a>
                      ) : (
                        <span>{place.name}</span>
                      )}
                      {place.note ? (
                        <span className="rt-poi-avail-place-note">{place.note}</span>
                      ) : null}
                    </th>
                    {nights.map((night) => (
                      <AvailabilityCellView
                        key={night.date}
                        night={night}
                        place={place}
                        cell={place.cells.get(night.date) ?? UNKNOWN_CELL}
                      />
                    ))}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>

          <AvailabilityLegend places={places} nights={nights} />
        </>
      )}
    </section>
  );
}

function AvailabilityCellView({
  night,
  place,
  cell,
}: {
  night: AvailabilityNight;
  place: AvailabilityPlace;
  cell: AvailabilityCell;
}) {
  const meta = availabilityStatusMeta(cell.status);
  const open = cell.status === 'available' ? cell.open : undefined;
  const nightName = [night.label, night.sublabel].filter(Boolean).join(' ');
  const spoken =
    open == null
      ? `${place.name}, ${nightName}: ${meta.aria}`
      : `${place.name}, ${nightName}: ${meta.aria}, ${open} ${open === 1 ? 'site' : 'sites'}`;

  return (
    <td className={`rt-poi-avail-cell rt-poi-avail-cell--${meta.kind}`} aria-label={spoken}>
      {/* Glyph first, colour second. The glyph is the state; the colour only makes
          it faster to scan. */}
      <span aria-hidden="true" className="rt-poi-avail-glyph">
        {meta.label}
      </span>
      {open == null ? null : (
        <span aria-hidden="true" className="rt-poi-avail-count">
          {open}
        </span>
      )}
    </td>
  );
}

/**
 * The key, listing only the states actually on screen.
 *
 * A fixed six-entry legend would explain "past" on a table with no past nights,
 * which trains the reader to stop reading it.
 */
function AvailabilityLegend({
  places,
  nights,
}: {
  places: readonly AvailabilityPlace[];
  nights: readonly AvailabilityNight[];
}) {
  const present = new Set<AvailabilityStatus>();
  for (const place of places) {
    for (const night of nights) {
      present.add((place.cells.get(night.date) ?? UNKNOWN_CELL).status);
    }
  }
  const shown = [...present].map((status) => availabilityStatusMeta(status));

  return (
    <ul className="rt-poi-avail-legend">
      {shown.map((meta) => (
        <li key={meta.value} className={`rt-poi-avail-key rt-poi-avail-key--${meta.kind}`}>
          <span aria-hidden="true" className="rt-poi-avail-glyph">
            {meta.label}
          </span>
          <span>{meta.text}</span>
        </li>
      ))}
    </ul>
  );
}

/** Build a row's cells from plain pairs, so callers do not construct a `Map` by hand. */
export function nightCells(entries: readonly (readonly [string, AvailabilityCell])[]): ReadonlyMap<string, AvailabilityCell> {
  return new Map(entries);
}
