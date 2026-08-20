import { describe, expect, test } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { Watch } from '@/api/watches-api';
import { formatWatchDate, relativeTime, watchFallbackName, WatchTable } from './WatchTable';

const watch = (fields: Partial<Watch> = {}): Watch => ({
  id: 1,
  targets: [],
  poi_id: 42,
  campsite_filters: {},
  start_date: '2026-07-08',
  end_date: '2026-07-15',
  trigger_kinds: ['slack_notify'],
  trigger_config: {},
  stop_when_triggered: true,
  status: 'active',
  created_at: '2026-06-01T00:00:00Z',
  updated_at: '2026-06-01T00:00:00Z',
  ...fields,
});

const noop = () => {};

function renderTable(watches: Watch[], overrides: Partial<Parameters<typeof WatchTable>[0]> = {}) {
  return render(
    <WatchTable
      watches={watches}
      poiNames={new Map([[42, 'Manzanita Lake']])}
      onEdit={noop}
      onSetStatus={noop}
      onDelete={noop}
      onNewWatch={noop}
      {...overrides}
    />,
  );
}

describe('formatWatchDate', () => {
  test('formats in UTC, not the local zone', () => {
    expect(formatWatchDate('2026-07-08')).toBe('Jul 8');
    expect(formatWatchDate('2026-01-01')).toBe('Jan 1');
  });

  test.each([[null], [undefined], ['']])('returns null for %j', (iso) => {
    expect(formatWatchDate(iso)).toBeNull();
  });

  test('echoes an unparseable value rather than showing Invalid Date', () => {
    expect(formatWatchDate('not-a-date')).toBe('not-a-date');
  });
});

describe('relativeTime', () => {
  const now = Date.parse('2026-08-08T12:00:00Z');

  test.each([
    ['2026-08-08T11:59:30Z', 'just now'],
    ['2026-08-08T11:55:00Z', '5m ago'],
    ['2026-08-08T09:00:00Z', '3h ago'],
    ['2026-08-05T12:00:00Z', '3d ago'],
  ])('renders %s as %s', (iso, expected) => {
    expect(relativeTime(iso, now)).toBe(expected);
  });

  test('clamps a future timestamp to just now', () => {
    expect(relativeTime('2026-08-08T12:05:00Z', now)).toBe('just now');
  });

  test('echoes an unparseable value', () => {
    expect(relativeTime('nope', now)).toBe('nope');
  });
});

describe('watchFallbackName', () => {
  test('uses the campsite name when there is no POI', () => {
    expect(watchFallbackName(watch({ poi_id: null, campsite: { id: 5, name: 'Site 12' } }))).toBe(
      'Site 12',
    );
  });

  test('prefixes the loop when the campsite has one', () => {
    expect(
      watchFallbackName(
        watch({ poi_id: null, campsite: { id: 5, name: 'Site 12', loop_name: 'Loop A' } }),
      ),
    ).toBe('Loop A / Site 12');
  });

  test('falls back to the watch id', () => {
    expect(watchFallbackName(watch({ id: 9, poi_id: null, campsite: null }))).toBe('Watch #9');
  });
});

describe('empty state', () => {
  test('shows a prompt instead of an empty table', () => {
    renderTable([]);

    expect(screen.getByText('No watches yet')).toBeInTheDocument();
    expect(screen.queryByRole('table')).not.toBeInTheDocument();
  });
});

describe('rows', () => {
  test('links the POI cell to the map, resolving its name', () => {
    renderTable([watch()]);

    const link = screen.getByRole('link', { name: 'Manzanita Lake' });
    expect(link).toHaveAttribute('href', '/?poi=42');
  });

  test('falls back to POI {id} for an unresolved name', () => {
    renderTable([watch({ poi_id: 99 })]);

    expect(screen.getByRole('link', { name: 'POI 99' })).toBeInTheDocument();
  });

  test('labels the triggers', () => {
    renderTable([watch({ trigger_kinds: ['slack_notify', 'email_notify', 'atc'] })]);

    expect(screen.getByText('Slack')).toBeInTheDocument();
    expect(screen.getByText('Email')).toBeInTheDocument();
    expect(screen.getByText('Cart')).toBeInTheDocument();
  });

  test('shows the status', () => {
    renderTable([watch({ status: 'paused' })]);

    expect(screen.getByText('Paused')).toBeInTheDocument();
  });

  test('shows an error rather than an age for a failed run', () => {
    renderTable([
      watch({ last_run_at: '2026-08-08T11:00:00Z', last_run_status: 'failed', last_run_error: 'boom' }),
    ]);

    expect(screen.getByText('error')).toBeInTheDocument();
  });
});

describe('actions', () => {
  test('pauses an active watch', async () => {
    const calls: [number, string][] = [];
    renderTable([watch({ id: 3, status: 'active' })], {
      onSetStatus: (id, status) => calls.push([id, status]),
    });

    await userEvent.click(screen.getByRole('button', { name: 'Pause' }));

    expect(calls).toEqual([[3, 'paused']]);
  });

  test('resumes a paused watch', async () => {
    const calls: [number, string][] = [];
    renderTable([watch({ id: 3, status: 'paused' })], {
      onSetStatus: (id, status) => calls.push([id, status]),
    });

    await userEvent.click(screen.getByRole('button', { name: 'Resume' }));

    expect(calls).toEqual([[3, 'active']]);
  });

  test('edits by id', async () => {
    const edited: number[] = [];
    renderTable([watch({ id: 7 })], { onEdit: (id) => edited.push(id) });

    await userEvent.click(screen.getByRole('button', { name: 'Edit' }));

    expect(edited).toEqual([7]);
  });

  test('requires a second click to delete', async () => {
    const deleted: number[] = [];
    renderTable([watch({ id: 7 })], { onDelete: (id) => deleted.push(id) });

    await userEvent.click(screen.getByRole('button', { name: 'Delete' }));
    expect(deleted).toEqual([]);

    await userEvent.click(screen.getByRole('button', { name: 'Confirm delete' }));
    expect(deleted).toEqual([7]);
  });

  test('arms only the row that was clicked', async () => {
    renderTable([watch({ id: 1 }), watch({ id: 2 })], {
      poiNames: new Map(),
    });

    const deleteButtons = screen.getAllByRole('button', { name: 'Delete' });
    await userEvent.click(deleteButtons[0]!);

    expect(screen.getAllByRole('button', { name: 'Delete' })).toHaveLength(1);
    expect(screen.getAllByRole('button', { name: 'Confirm delete' })).toHaveLength(1);
  });

  test('disables the actions while a write is in flight', () => {
    renderTable([watch()], { busy: true });

    expect(screen.getByRole('button', { name: 'Edit' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Delete' })).toBeDisabled();
  });
});

describe('sorting', () => {
  const rows = [watch({ id: 1 }), watch({ id: 3 }), watch({ id: 2 })];

  const idCells = (): string[] =>
    screen
      .getAllByRole('row')
      .slice(1) // drop the header
      .map((row) => row.querySelectorAll('td')[0]?.textContent ?? '');

  test('defaults to id descending', () => {
    renderTable(rows, { poiNames: new Map() });

    expect(idCells()).toEqual(['3', '2', '1']);
  });

  test('a click on the sorted column flips the direction', async () => {
    renderTable(rows, { poiNames: new Map() });

    await userEvent.click(screen.getByRole('button', { name: 'Sort by ID' }));

    expect(idCells()).toEqual(['1', '2', '3']);
  });

  test('sorts by another column descending first', async () => {
    renderTable(
      [
        watch({ id: 1, start_date: '2026-07-01' }),
        watch({ id: 2, start_date: '2026-09-01' }),
        watch({ id: 3, start_date: '2026-08-01' }),
      ],
      { poiNames: new Map() },
    );

    await userEvent.click(screen.getByRole('button', { name: 'Sort by Date' }));

    expect(idCells()).toEqual(['2', '3', '1']);
  });

  test('keeps blanks last in both directions', async () => {
    const withBlanks = [
      watch({ id: 1, last_run_at: '2026-08-01T00:00:00Z' }),
      watch({ id: 2, last_run_at: null }),
      watch({ id: 3, last_run_at: '2026-08-05T00:00:00Z' }),
    ];
    renderTable(withBlanks, { poiNames: new Map() });

    await userEvent.click(screen.getByRole('button', { name: 'Sort by Last checked' }));
    expect(idCells()).toEqual(['3', '1', '2']);

    await userEvent.click(screen.getByRole('button', { name: 'Sort by Last checked' }));
    expect(idCells()).toEqual(['1', '3', '2']);
  });
});
