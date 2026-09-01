// The site-by-date grid.
//
// Row rules live in `matrix-rows.ts`. Three behaviours matter here:
//
// **A cell is a button only when tapping it can do something.** An available cell with
// a bookable deep link becomes a booking button; a reserved or first-come cell becomes
// a watch button, but only if the user could actually set one; everything else is inert
// text. A cell that looks tappable and is not is worse than a plain one.
//
// **Booking is two taps, and that is deliberate.** The first tap arms the cell (its
// label becomes "Book"), the second opens the provider. These are 66px-wide cells in a
// horizontally scrolling grid on a phone: a single tap would open a booking tab every
// time a thumb brushed the wrong column. Arming also means only one cell is armed at a
// time, so the label itself says which night is about to be booked.
//
// **The frozen first column is a real table column, not an overlay.** `position:
// sticky` on the `th` inside a scrolling container, sized by a CSS custom property the
// resize drag updates. See the `.cg-site-matrix-*` rules in availability.css.
import { useCallback, useEffect, useRef, useState } from 'react';
import { LinkButton } from '@ui';
import type { Campsite } from '@/api/campsite-api';
import type { FusedDay } from './fuse';
import { SiteDetail } from './SiteDetail';
import {
  DEFAULT_MATRIX_FILTERS,
  SORT_OPTIONS,
  availabilityIndex,
  cellState,
  filterCampsites,
  filterOptions,
  isWatchableKind,
  normalizeFilters,
  rowId,
  siteName,
  siteTitleText,
  sortCampsites,
  sortedCampsites,
  type MatrixFilters,
  type MatrixSort,
} from './matrix-rows';
import { hasReservationUrlTemplate, type ReservationUrlTemplates } from './booking-links';
import { dayOfMonthLabel, dowLabel } from './week-labels';
import { clampSiteColumnWidth, saveSiteColumnWidth } from './site-column';
import { CellBookPopover } from './CellBookPopover';
import { cartActionFor, type CartAction } from './cart-action';

/** Width of one date column, matching `.cg-site-matrix-date` in the stylesheet. */
const DATE_COLUMN_WIDTH_PX = 66;
/** Placeholder rows in the skeleton — enough to fill the drawer, few enough to be fast. */
const SKELETON_ROW_COUNT = 6;

/** The cell currently armed for booking. */
export interface ArmedBook {
  campsiteId: string;
  date: string;
}

export interface SiteMatrixProps {
  days: readonly FusedDay[];
  catalog: {
    campsites: readonly Campsite[];
    reservationUrlTemplates: ReservationUrlTemplates;
    state: 'loading' | 'success' | 'error';
    error: string | null;
    retry: () => void;
  };
  view: {
    filters: MatrixFilters;
    siteColumnWidth: number;
    selectedSiteId: string | null;
    armedBook: ArmedBook | null;
    watchedDates: ReadonlySet<string>;
    canWatch: boolean;
    /** True when this caller could actually hold a site — the same condition
     *  that enables the watch editor's ATC toggle. */
    canAddToCart: boolean;
    cartAction: CartAction | null;
  };
  events: {
    filtersChanged: (filters: MatrixFilters) => void;
    siteColumnResized: (width: number) => void;
    siteSelected: (campsiteId: string | null) => void;
    bookingArmed: (armed: ArmedBook | null) => void;
    bookingOpened: (campsiteId: string, date: string) => void;
    cartRequested: (campsiteId: string, date: string) => void;
    dateSelected: (date: string) => void;
    watchOpened: (anchor: HTMLElement, date: string) => void;
  };
  weekActions: React.ReactNode;
}

export function SiteMatrix(props: SiteMatrixProps) {
  const { days, catalog, view, events, weekActions } = props;
  const { campsites, state: sitesState, error: sitesError, retry: onRetrySites } = catalog;
  const { siteColumnWidth } = view;
  const visibleDays = days.filter((day) => day?.date);
  if (visibleDays.length === 0) return null;

  if (sitesState === 'loading') {
    return (
      <SiteMatrixSkeleton
        days={visibleDays}
        siteColumnWidth={siteColumnWidth}
        actions={weekActions}
      />
    );
  }

  if (sitesState === 'error') {
    return (
      <MatrixSection title="Sites by date" actions={weekActions}>
        <div className="cg-site-matrix-status cg-site-matrix-error">
          {sitesError || "Couldn't load sites"}{' '}
          <LinkButton className="cg-sites-retry" onClick={onRetrySites}>
            Retry
          </LinkButton>
        </div>
      </MatrixSection>
    );
  }

  const allRows = sortedCampsites(campsites, visibleDays);
  if (allRows.length === 0) {
    return (
      <MatrixSection title="Sites by date" actions={weekActions}>
        <div className="cg-site-matrix-status">No reservable sites found for this campground.</div>
      </MatrixSection>
    );
  }

  const filters = normalizeFilters(view.filters);
  const availabilityByDate = availabilityIndex(visibleDays);
  const rows = sortCampsites(filterCampsites(allRows, filters), filters.sort, {
    availabilityByDate,
    visibleDays,
  });

  const tools = (
    <MatrixTools
      filters={filters}
      loopOptions={filterOptions(allRows, 'loop_name')}
      typeOptions={filterOptions(allRows, 'kind')}
      onChange={events.filtersChanged}
    />
  );

  if (rows.length === 0) {
    return (
      <MatrixSection title={`0 of ${allRows.length} Sites by date`} tools={tools} actions={weekActions}>
        <div className="cg-site-matrix-status">No sites match these filters.</div>
      </MatrixSection>
    );
  }

  // The count is the honest headline: "12 of 240" says the filters are doing
  // something, where a bare "12" looks like a very small campground.
  const title =
    rows.length === allRows.length
      ? `${rows.length} Sites by date`
      : `${rows.length} of ${allRows.length} Sites by date`;

  return (
    <MatrixSection title={title} tools={tools} actions={weekActions}>
      <MatrixScroll siteColumnWidth={siteColumnWidth} dateCount={visibleDays.length}>
        <table className="cg-site-matrix-table">
          <thead>
            <tr>
              <th scope="col" className="cg-site-matrix-site cg-site-matrix-site-heading">
                <span>Site</span>
                <SiteColumnResizer
                  width={siteColumnWidth}
                  onChange={events.siteColumnResized}
                />
              </th>
              {visibleDays.map((day) => (
                <th scope="col" className="cg-site-matrix-date" key={day.date}>
                  <button
                    type="button"
                    className="cg-site-matrix-date-button"
                    onClick={() => events.dateSelected(day.date)}
                  >
                    <span>{dowLabel(day.date)}</span>
                    <strong>{dayOfMonthLabel(day.date)}</strong>
                  </button>
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {rows.map((row) => (
              <MatrixRow
                key={rowId(row)}
                row={row}
                visibleDays={visibleDays}
                availabilityByDate={availabilityByDate}
                selectedSiteId={view.selectedSiteId}
                onSelectSite={events.siteSelected}
                reservationUrlTemplates={catalog.reservationUrlTemplates}
                armedBook={view.armedBook}
                onArmBook={events.bookingArmed}
                onOpenBooking={events.bookingOpened}
                canAddToCart={view.canAddToCart}
                cartAction={view.cartAction}
                onAddToCart={events.cartRequested}
                watchedDates={view.watchedDates}
                canWatch={view.canWatch}
                onOpenWatch={events.watchOpened}
              />
            ))}
          </tbody>
        </table>
      </MatrixScroll>
    </MatrixSection>
  );
}

/**
 * The skeleton grid.
 *
 * Real date headers when we have them, because the columns do not move once data
 * arrives — a skeleton whose headers change on load reads as two different tables.
 */
export function SiteMatrixSkeleton({
  days = [],
  siteColumnWidth,
  actions,
  rowCount = SKELETON_ROW_COUNT,
}: {
  days?: readonly { date: string }[];
  siteColumnWidth: number;
  actions?: React.ReactNode;
  rowCount?: number;
}) {
  const visibleDays = days.filter((day) => day?.date);
  const dateCount = visibleDays.length || 7;

  return (
    <MatrixSection title="Sites by date" tools={<SkeletonTools />} actions={actions}>
      <MatrixScroll
        siteColumnWidth={siteColumnWidth}
        dateCount={dateCount}
        className="cg-site-matrix-skeleton"
        aria-busy="true"
      >
        <table className="cg-site-matrix-table">
          <thead>
            <tr>
              <th scope="col" className="cg-site-matrix-site cg-site-matrix-site-heading">
                <span>Site</span>
              </th>
              {visibleDays.length > 0
                ? visibleDays.map((day) => (
                    <th scope="col" className="cg-site-matrix-date" key={day.date}>
                      <span>{dowLabel(day.date)}</span> <strong>{dayOfMonthLabel(day.date)}</strong>
                    </th>
                  ))
                : Array.from({ length: dateCount }, (_, index) => (
                      <th
                        scope="col"
                        className="cg-site-matrix-date cg-site-matrix-skeleton-cell"
                        key={index}
                      />
                    ))}
            </tr>
          </thead>
          <tbody>
            {Array.from({ length: rowCount }, (_, rowIndex) => (
              <tr key={rowIndex}>
                <th scope="row" className="cg-site-matrix-site cg-site-matrix-skeleton-cell">
                  <span className="cg-site-matrix-skeleton-bar cg-site-matrix-skeleton-name" />
                  <span className="cg-site-matrix-skeleton-bar cg-site-matrix-skeleton-meta" />
                </th>
                {Array.from({ length: dateCount }, (_, cellIndex) => (
                  <td
                    className="cg-site-matrix-cell cg-site-matrix-skeleton-cell"
                    key={cellIndex}
                  >
                    <span className="cg-site-matrix-skeleton-bar cg-site-matrix-skeleton-pill" />
                  </td>
                ))}
              </tr>
            ))}
          </tbody>
        </table>
      </MatrixScroll>
    </MatrixSection>
  );
}

function MatrixSection({
  title,
  tools,
  actions,
  children,
}: {
  title: string;
  tools?: React.ReactNode;
  actions?: React.ReactNode;
  children: React.ReactNode;
}) {
  return (
    <section className="cg-site-matrix" aria-label="Sites by date">
      <div className="cg-site-matrix-head">
        <div>
          <div className="cg-site-matrix-title">{title}</div>
          <div className="cg-site-matrix-legend">
            <span className="cg-site-matrix-key cg-site-matrix-key-available" title="Available">
              A
            </span>
            <span
              className="cg-site-matrix-key cg-site-matrix-key-first-come"
              title="First come first served"
            >
              FF
            </span>
            <span className="cg-site-matrix-key cg-site-matrix-key-reserved" title="Reserved">
              R
            </span>
            <span className="cg-site-matrix-key cg-site-matrix-key-closed" title="Closed">
              C
            </span>
            <span className="cg-site-matrix-key cg-site-matrix-key-unknown" title="Unknown">
              ?
            </span>
          </div>
        </div>
        <div className="cg-site-matrix-actions">{actions}</div>
      </div>
      {tools}
      {children}
    </section>
  );
}

/**
 * The scroll container, and the three custom properties the grid is sized by.
 *
 * `--rt-site-matrix-viewport-width` is measured rather than declared: the sticky
 * column's shadow and the "scroll for more dates" affordance both need to know how
 * much of the table is visible, which only the DOM knows.
 */
function MatrixScroll({
  siteColumnWidth,
  dateCount,
  className = '',
  children,
  ...rest
}: {
  siteColumnWidth: number;
  dateCount: number;
  className?: string;
  children: React.ReactNode;
} & React.HTMLAttributes<HTMLDivElement>) {
  const ref = useRef<HTMLDivElement>(null);

  const measure = useCallback(() => {
    const element = ref.current;
    if (!element) return;
    element.style.setProperty('--rt-site-matrix-viewport-width', `${element.clientWidth}px`);
  }, []);

  // No dependency list: the visible width changes when the row set changes (a
  // scrollbar appears, the drawer reflows), and there is no value to depend on that
  // captures that. Measuring on every commit is cheap; re-binding the listener on
  // every commit would not be, so that gets its own effect.
  useEffect(measure);
  useEffect(() => {
    window.addEventListener('resize', measure);
    return () => window.removeEventListener('resize', measure);
  }, [measure]);

  return (
    <div
      className={`cg-site-matrix-scroll ${className}`.trim()}
      ref={ref}
      style={{
        '--rt-site-dates-width': `${Math.max(1, dateCount) * DATE_COLUMN_WIDTH_PX}px`,
        '--rt-site-column-width': `${Math.round(siteColumnWidth)}px`,
      } as React.CSSProperties}
      {...rest}
    >
      {children}
    </div>
  );
}

/**
 * The drag handle on the Site column's edge.
 *
 * Pointer events with capture, so a drag that leaves the handle keeps tracking — and
 * the width is written straight to the DOM custom property during the drag, with React
 * state updated only on release. A re-render per pointermove would rebuild every cell
 * in a 240-row table.
 */
function SiteColumnResizer({
  width,
  onChange,
}: {
  width: number;
  onChange: (width: number) => void;
}) {
  const drag = useRef<{ startX: number; startWidth: number; scroll: HTMLElement | null } | null>(
    null,
  );
  const latest = useRef(width);

  const onPointerDown = useCallback(
    (event: React.PointerEvent<HTMLButtonElement>) => {
      event.preventDefault();
      event.stopPropagation();
      const handle = event.currentTarget;
      drag.current = {
        startX: event.clientX,
        startWidth: width,
        scroll: handle.closest('.cg-site-matrix-scroll') as HTMLElement | null,
      };
      latest.current = width;
      document.body.classList.add('cg-site-column-resizing');
      try {
        handle.setPointerCapture(event.pointerId);
      } catch {
        // A synthetic pointer in a test has nothing to capture.
      }
    },
    [width],
  );

  const onPointerMove = useCallback((event: React.PointerEvent<HTMLButtonElement>) => {
    const active = drag.current;
    if (!active) return;
    const next = clampSiteColumnWidth(active.startWidth + event.clientX - active.startX);
    latest.current = next;
    active.scroll?.style.setProperty('--rt-site-column-width', `${next}px`);
  }, []);

  const endDrag = useCallback(() => {
    if (!drag.current) return;
    drag.current = null;
    document.body.classList.remove('cg-site-column-resizing');
    saveSiteColumnWidth(latest.current);
    onChange(latest.current);
  }, [onChange]);

  return (
    <button
      type="button"
      className="cg-site-matrix-resizer"
      aria-label="Resize site column"
      title="Resize site column"
      onPointerDown={onPointerDown}
      onPointerMove={onPointerMove}
      onPointerUp={endDrag}
      onPointerCancel={endDrag}
    />
  );
}

function MatrixTools({
  filters,
  loopOptions,
  typeOptions,
  onChange,
}: {
  filters: MatrixFilters;
  loopOptions: string[];
  typeOptions: string[];
  onChange: (filters: MatrixFilters) => void;
}) {
  return (
    <div className="cg-site-matrix-tools">
      <input
        type="search"
        className="cg-site-matrix-filter cg-site-matrix-search"
        value={filters.query}
        placeholder="Filter sites"
        aria-label="Filter sites"
        autoComplete="off"
        onChange={(event) => onChange({ ...filters, query: event.target.value })}
      />
      <FilterSelect
        label="All loops"
        ariaLabel="Filter by loop"
        value={filters.loop}
        options={loopOptions.map((option) => ({ value: option, label: option }))}
        onChange={(loop) => onChange({ ...filters, loop })}
      />
      <FilterSelect
        label="All types"
        ariaLabel="Filter by site type"
        value={filters.type}
        options={typeOptions.map((option) => ({ value: option, label: option }))}
        onChange={(type) => onChange({ ...filters, type })}
      />
      <FilterSelect
        label={null}
        ariaLabel="Sort sites"
        value={filters.sort}
        options={SORT_OPTIONS.map((option) => ({ value: option.value, label: option.label }))}
        onChange={(sort) => onChange({ ...filters, sort: sort as MatrixSort })}
      />
    </div>
  );
}

/** A plain `<select>`, not LDS's: it lives in a 4-up toolbar sized by the grid's CSS. */
function FilterSelect({
  label,
  ariaLabel,
  value,
  options,
  onChange,
}: {
  /** The "no filter" option, or null for a select with no empty state (the sort). */
  label: string | null;
  ariaLabel: string;
  value: string;
  options: { value: string; label: string }[];
  onChange: (value: string) => void;
}) {
  return (
    <select
      className="cg-site-matrix-filter"
      aria-label={ariaLabel}
      value={value}
      onChange={(event) => onChange(event.target.value)}
    >
      {label == null ? null : <option value="">{label}</option>}
      {options.map((option) => (
        <option value={option.value} key={option.value}>
          {option.label}
        </option>
      ))}
    </select>
  );
}

/** The row's own inputs, plus the cell inputs it forwards unchanged. */
type MatrixRowProps = Omit<MatrixCellProps, 'row' | 'day' | 'availableIds' | 'siteLabel'> & {
  row: Partial<Campsite>;
  visibleDays: readonly FusedDay[];
  availabilityByDate: Map<string, Set<string>>;
  selectedSiteId: string | null;
  onSelectSite: (campsiteId: string | null) => void;
};

function MatrixRow({
  row,
  visibleDays,
  availabilityByDate,
  selectedSiteId,
  onSelectSite,
  reservationUrlTemplates,
  armedBook,
  onArmBook,
  onOpenBooking,
  canAddToCart,
  cartAction,
  onAddToCart,
  watchedDates,
  canWatch,
  onOpenWatch,
}: MatrixRowProps) {
  const id = rowId(row);
  const label = siteName(row);
  const title = siteTitleText(row, label);
  const isSelected = id === String(selectedSiteId);
  const loop = typeof row.loop_name === 'string' ? row.loop_name.trim() : '';

  return (
    <>
      <tr className={isSelected ? 'cg-site-matrix-row-selected' : undefined}>
        <th scope="row" className="cg-site-matrix-site" title={title}>
          <button
            type="button"
            className="cg-site-matrix-site-button"
            title={title}
            aria-label={`View details for ${title}`}
            aria-expanded={isSelected}
            onClick={() => onSelectSite(isSelected ? null : id)}
          >
            <span className="cg-site-matrix-site-title">
              {loop ? <span className="cg-site-matrix-loop-prefix">{loop} / </span> : null}
              <span className="cg-site-matrix-name">{label}</span>
            </span>
          </button>
        </th>
        {visibleDays.map((day) => (
          <MatrixCell
            key={day.date}
            row={row}
            day={day}
            availableIds={availabilityByDate.get(day.date)}
            siteLabel={label}
            reservationUrlTemplates={reservationUrlTemplates}
            armedBook={armedBook}
            onArmBook={onArmBook}
            onOpenBooking={onOpenBooking}
            canAddToCart={canAddToCart}
            cartAction={cartAction}
            onAddToCart={onAddToCart}
            watchedDates={watchedDates}
            canWatch={canWatch}
            onOpenWatch={onOpenWatch}
          />
        ))}
      </tr>
      {isSelected ? (
        <tr className="cg-site-matrix-detail-row">
          <td colSpan={1 + visibleDays.length}>
            <SiteDetail site={row} reservationUrlTemplates={reservationUrlTemplates} />
          </td>
        </tr>
      ) : null}
    </>
  );
}

/**
 * Exactly what one cell needs, named.
 *
 * Not `Omit<SiteMatrixProps, …>` plus a spread, which is what this was: the spread sat
 * *after* the explicit props, so adding a `row` or `siteLabel` to the grid's own props
 * would silently have overridden the per-cell value with the whole-grid one. It also
 * handed every cell the week-nav element and eight grid-level callbacks it never reads
 * — 210 of them in a 30-site week.
 */
interface MatrixCellProps {
  row: Partial<Campsite>;
  day: FusedDay;
  availableIds: Set<string> | undefined;
  siteLabel: string;
  reservationUrlTemplates: ReservationUrlTemplates;
  armedBook: ArmedBook | null;
  onArmBook: (armed: ArmedBook | null) => void;
  onOpenBooking: (campsiteId: string, date: string) => void;
  canAddToCart: boolean;
  cartAction: CartAction | null;
  onAddToCart: (campsiteId: string, date: string) => void;
  watchedDates: ReadonlySet<string>;
  canWatch: boolean;
  onOpenWatch: (anchor: HTMLElement, date: string) => void;
}

function MatrixCell({
  row,
  day,
  availableIds,
  siteLabel,
  reservationUrlTemplates,
  armedBook,
  onArmBook,
  onOpenBooking,
  canAddToCart,
  cartAction,
  onAddToCart,
  watchedDates,
  canWatch,
  onOpenWatch,
}: MatrixCellProps) {
  const [cellAnchor, setCellAnchor] = useState<HTMLElement | null>(null);
  const state = cellState(row, day, availableIds);
  const id = rowId(row);
  const aria = `${siteLabel} ${day.date}: ${state.aria}`;
  const cellClass = `cg-site-matrix-cell cg-site-matrix-cell-${state.kind}`;

  if (state.value !== 'available') {
    const watched = watchedDates.has(day.date);
    // A watched cell stays interactive even for a user who can no longer create
    // watches, so an existing one can always be managed.
    if (isWatchableKind(state.kind) && (canWatch || watched)) {
      return (
        <td className={cellClass}>
          <button
            type="button"
            className={`cg-site-matrix-cell-button cg-site-matrix-cell-watch${watched ? ' is-watched' : ''}`}
            aria-label={
              watched
                ? `${aria}; availability watch set, tap to manage`
                : `${aria}; tap to set an availability watch`
            }
            onClick={(event) => onOpenWatch(event.currentTarget, day.date)}
          >
            {state.label}
          </button>
        </td>
      );
    }
    return (
      <td className={cellClass} aria-label={aria}>
        <span className="cg-site-matrix-cell-button">{state.label}</span>
      </td>
    );
  }

  // Available, but this provider gives us no deep link — so there is nothing for a
  // tap to open, and the cell says "A" without pretending to be a button.
  if (!hasReservationUrlTemplate(row, reservationUrlTemplates)) {
    return (
      <td className={cellClass} aria-label={aria}>
        <span className="cg-site-matrix-cell-button">{state.label}</span>
      </td>
    );
  }

  const armed =
    armedBook != null && String(armedBook.campsiteId) === id && armedBook.date === day.date;
  const cellAction = cartActionFor(cartAction, id, day.date);

  // A hold in flight owns the cell: locked, spinning, and not a second request.
  if (cellAction?.kind === 'pending') {
    return (
      <td className={cellClass}>
        <span
          className="cg-site-matrix-cell-button is-cart-pending"
          role="status"
          aria-label={`${aria}; holding this site in your cart`}
        >
          <CellSpinner />
        </span>
      </td>
    );
  }

  // Held until the next refetch replaces it with the vendor's own answer.
  if (cellAction?.kind === 'held') {
    return (
      <td className={cellClass}>
        <span className="cg-site-matrix-cell-button is-cart-held" aria-label={`${aria}; held in your cart`}>
          <CellCheckIcon />
          Cart
        </span>
      </td>
    );
  }

  const openPopover = armed && canAddToCart;

  return (
    <td className={cellClass}>
      <button
        type="button"
        ref={setCellAnchor}
        className={`cg-site-matrix-cell-button${armed ? ' is-armed' : ''}`}
        aria-label={
          armed
            ? canAddToCart
              ? `${aria}; choose how to book`
              : `${aria}; Book, click to open booking page`
            : `${aria}; click to book`
        }
        onClick={() => {
          // Without the capability this is exactly the two-tap flip it always
          // was — that population sees no change at all.
          if (!armed) onArmBook({ campsiteId: id, date: day.date });
          else if (!canAddToCart) onOpenBooking(id, day.date);
        }}
      >
        {armed ? 'Book' : state.label}
      </button>
      {openPopover && cellAnchor ? (
        <CellBookPopover
          anchor={cellAnchor}
          onOpenBooking={() => onOpenBooking(id, day.date)}
          onAddToCart={() => onAddToCart(id, day.date)}
          onClose={() => onArmBook(null)}
        />
      ) : null}
    </td>
  );
}

/** The in-cell spinner. Inline because it is one 14px mark, not an icon-set entry. */
function CellSpinner() {
  return (
    <svg className="cg-cell-spinner" viewBox="0 0 24 24" fill="none" aria-hidden="true">
      <circle cx="12" cy="12" r="9" stroke="currentColor" strokeOpacity="0.25" strokeWidth="3" />
      <path d="M21 12a9 9 0 0 0-9-9" stroke="currentColor" strokeWidth="3" strokeLinecap="round" />
    </svg>
  );
}

function CellCheckIcon() {
  return (
    <svg
      className="cg-cell-held-icon"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="3"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
    >
      <path d="m4 12.5 5.5 5.5L20 6" />
    </svg>
  );
}

function SkeletonTools() {
  return (
    <div className="cg-site-matrix-tools cg-site-matrix-skeleton-tools" aria-hidden="true">
      {Array.from({ length: 4 }, (_, index) => (
        <span className="cg-site-matrix-filter cg-site-matrix-skeleton-control" key={index} />
      ))}
    </div>
  );
}

export { DEFAULT_MATRIX_FILTERS };
