import { useCallback, useMemo, useState, type CSSProperties, type KeyboardEvent } from 'react';
import type { AvailabilityDay } from '@/api/availability-api';
import type { Campsite } from '@/api/campsite-api';
import { availabilityStatusMeta, type AvailabilityStatus } from '@/lib/availability-status';
import { matrixCopy } from '@/lib/strings';
import { dayOfMonthLabel, dowLabel } from '@/lib/week-labels';
import { NO_CART_ACTIONS, cartActionFor, type CartActionCell, type CartActions } from './cart-action';
import { reachableCartGate, type CartGate, type ReachableCartGate } from './cart-gate';
import { SiteCartPopover } from './SiteCartPopover';
import {
  DEFAULT_MATRIX_FILTERS,
  SORT_OPTIONS,
  cellState,
  filterCampsites,
  loopOptions,
  matrixColumns,
  normalizeFilters,
  rowId,
  siteName,
  siteTitleText,
  sortCampsites,
  sortedCampsites,
  typeOptions,
  type FilterOption,
  type MatrixColumn,
  type MatrixFilters,
  type MatrixSort,
} from './matrix-rows';
import './site-matrix.css';

const DATE_COUNT_PROPERTY = '--rt-site-date-count';

const LEGEND_STATUSES: readonly AvailabilityStatus[] = [
  'available',
  'first_come',
  'reserved',
  'closed',
  'unknown',
];

const NO_FILTER_VALUE = '';

const BOOKABLE_STATUS: AvailabilityStatus = 'available';

const DISMISS_KEY = 'Escape';

export interface SiteMatrixProps {
  dates: readonly string[];
  days: readonly AvailabilityDay[];
  campsites?: readonly Campsite[];
  selected: string | null;
  onSelect: (date: string) => void;
  cart?: CartGate;
  cartActions?: CartActions;
}

export function SiteMatrix({
  dates,
  days,
  campsites,
  selected,
  onSelect,
  cart,
  cartActions = NO_CART_ACTIONS,
}: SiteMatrixProps) {
  const [filters, setFilters] = useState<MatrixFilters>(DEFAULT_MATRIX_FILTERS);

  const columns = useMemo(() => matrixColumns(dates, days), [dates, days]);
  const allRows = useMemo(() => sortedCampsites(campsites, columns), [campsites, columns]);
  const rows = useMemo(
    () => sortCampsites(filterCampsites(allRows, filters), filters.sort, columns),
    [allRows, filters, columns],
  );

  if (columns.length === 0) return null;

  return (
    <section className="rt-site-matrix" aria-label={matrixCopy.group}>
      <div className="rt-site-matrix__head">
        <h3 className="rt-site-matrix__title">{matrixCopy.heading(rows.length, allRows.length)}</h3>
        <MatrixLegend />
      </div>
      {allRows.length === 0 ? null : (
        <MatrixTools
          filters={filters}
          loops={loopOptions(allRows)}
          types={typeOptions(allRows)}
          onChange={setFilters}
        />
      )}
      {rows.length === 0 ? (
        <p className="rt-site-matrix__status">
          {allRows.length === 0 ? matrixCopy.noSites : matrixCopy.noMatches}
        </p>
      ) : (
        <div
          className="rt-site-matrix__scroll"
          style={{ [DATE_COUNT_PROPERTY]: columns.length } as CSSProperties}
        >
          <table className="rt-site-matrix__table">
            <thead>
              <tr>
                <th scope="col" className="rt-site-matrix__site">
                  {matrixCopy.siteColumn}
                </th>
                {columns.map((column) => (
                  <th scope="col" className="rt-site-matrix__date" key={column.date}>
                    <button
                      type="button"
                      className="rt-site-matrix__date-button"
                      aria-pressed={column.date === selected}
                      aria-label={matrixCopy.pickNight(column.spoken)}
                      onClick={() => onSelect(column.date)}
                    >
                      <span>{dowLabel(column.date)}</span>
                      <strong>{dayOfMonthLabel(column.date)}</strong>
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
                  columns={columns}
                  cart={cart}
                  cartActions={cartActions}
                />
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  );
}

function MatrixRow({
  row,
  columns,
  cart,
  cartActions,
}: {
  row: Partial<Campsite>;
  columns: readonly MatrixColumn[];
  cart: CartGate | undefined;
  cartActions: CartActions;
}) {
  const label = siteName(row);
  const title = siteTitleText(row, label);
  const loop = row.loop_name?.trim();

  return (
    <tr>
      <th scope="row" className="rt-site-matrix__site" title={title}>
        <span className="rt-site-matrix__site-title">
          {loop ? <span className="rt-site-matrix__loop">{loop} / </span> : null}
          <span className="rt-site-matrix__name">{label}</span>
        </span>
      </th>
      {columns.map((column) => (
        <MatrixCell
          key={column.date}
          row={row}
          column={column}
          siteLabel={title}
          cart={cart}
          cartActions={cartActions}
        />
      ))}
    </tr>
  );
}

function MatrixCell({
  row,
  column,
  siteLabel,
  cart,
  cartActions,
}: {
  row: Partial<Campsite>;
  column: MatrixColumn;
  siteLabel: string;
  cart: CartGate | undefined;
  cartActions: CartActions;
}) {
  const meta = availabilityStatusMeta(cellState(row, column).status);
  const spoken = matrixCopy.cell(siteLabel, column.spoken, meta.aria);
  const className = `rt-site-matrix__cell rt-site-matrix__cell--${meta.kind}`;
  const gate = meta.value === BOOKABLE_STATUS ? reachableCartGate(cart) : null;

  if (gate === null) {
    return (
      <td className={className} aria-label={spoken}>
        <span aria-hidden="true">{meta.label}</span>
      </td>
    );
  }

  return (
    <CartCell
      className={className}
      glyph={meta.label}
      spoken={spoken}
      site={siteLabel}
      night={column.spoken}
      campsiteId={rowId(row)}
      date={column.date}
      gate={gate}
      cartActions={cartActions}
    />
  );
}

function CartCell({
  className,
  glyph,
  spoken,
  site,
  night,
  campsiteId,
  date,
  gate,
  cartActions,
}: {
  className: string;
  glyph: string;
  spoken: string;
  site: string;
  night: string;
  campsiteId: string;
  date: string;
  gate: ReachableCartGate;
  cartActions: CartActions;
}) {
  const [anchor, setAnchor] = useState<HTMLButtonElement | null>(null);
  const [open, setOpen] = useState(false);

  const cell = useMemo<CartActionCell>(() => ({ campsiteId, date }), [campsiteId, date]);
  const close = useCallback(() => setOpen(false), []);

  const mine = cartActionFor(cartActions, campsiteId, date);
  const state = mine?.kind === 'pending' ? 'pending' : mine?.kind === 'held' ? 'held' : null;
  const label =
    state === 'pending'
      ? matrixCopy.cellHolding(spoken)
      : state === 'held'
        ? matrixCopy.cellHeld(spoken)
        : matrixCopy.cellCart(spoken);

  const onKeyDown = (event: KeyboardEvent<HTMLTableCellElement>) => {
    if (!open || event.key !== DISMISS_KEY) return;
    event.preventDefault();
    event.stopPropagation();
    setOpen(false);
  };

  return (
    <td className={className} onKeyDown={onKeyDown}>
      <button
        type="button"
        ref={setAnchor}
        className={`rt-site-matrix__cell-button${state ? ` is-${state}` : ''}`}
        aria-label={label}
        aria-expanded={open}
        onClick={() => setOpen((was) => !was)}
      >
        <span aria-hidden="true">{glyph}</span>
      </button>
      {open && anchor ? (
        <SiteCartPopover
          anchor={anchor}
          cell={cell}
          site={site}
          night={night}
          gate={gate}
          actions={cartActions}
          onClose={close}
        />
      ) : null}
    </td>
  );
}

function MatrixLegend() {
  return (
    <ul className="rt-site-matrix__legend" aria-label={matrixCopy.key}>
      {LEGEND_STATUSES.map((status) => {
        const meta = availabilityStatusMeta(status);
        return (
          <li key={meta.value} className={`rt-site-matrix__key rt-site-matrix__key--${meta.kind}`}>
            <span aria-hidden="true">{meta.label}</span>
            <span className="rt-site-matrix__key-text">{meta.text}</span>
          </li>
        );
      })}
    </ul>
  );
}

function MatrixTools({
  filters,
  loops,
  types,
  onChange,
}: {
  filters: MatrixFilters;
  loops: readonly string[];
  types: readonly FilterOption[];
  onChange: (filters: MatrixFilters) => void;
}) {
  return (
    <div className="rt-site-matrix__tools">
      <input
        type="search"
        className="rt-site-matrix__filter rt-site-matrix__search"
        value={filters.query}
        placeholder={matrixCopy.filterSites}
        aria-label={matrixCopy.filterSites}
        autoComplete="off"
        onChange={(event) => onChange({ ...filters, query: event.target.value })}
      />
      <FilterSelect
        empty={matrixCopy.allLoops}
        label={matrixCopy.filterByLoop}
        value={filters.loop}
        options={loops.map((option) => ({ value: option, label: option }))}
        onChange={(loop) => onChange({ ...filters, loop })}
      />
      <FilterSelect
        empty={matrixCopy.allTypes}
        label={matrixCopy.filterByType}
        value={filters.type}
        options={types}
        onChange={(type) => onChange({ ...filters, type })}
      />
      <FilterSelect
        empty={null}
        label={matrixCopy.sortSites}
        value={filters.sort}
        options={SORT_OPTIONS}
        onChange={(sort) => onChange(normalizeFilters({ ...filters, sort: sort as MatrixSort }))}
      />
    </div>
  );
}

function FilterSelect({
  empty,
  label,
  value,
  options,
  onChange,
}: {
  empty: string | null;
  label: string;
  value: string;
  options: readonly FilterOption[];
  onChange: (value: string) => void;
}) {
  return (
    <select
      className="rt-site-matrix__filter rt-site-matrix__select"
      aria-label={label}
      value={value}
      onChange={(event) => onChange(event.target.value)}
    >
      {empty === null ? null : <option value={NO_FILTER_VALUE}>{empty}</option>}
      {options.map((option) => (
        <option value={option.value} key={option.value}>
          {option.label}
        </option>
      ))}
    </select>
  );
}

