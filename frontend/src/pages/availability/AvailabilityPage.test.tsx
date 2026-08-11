import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest';
import type { QueryClient } from '@tanstack/react-query';
import { createTestQueryClient } from '@/test/query-client';
import { act, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { AppProviders } from '@/app/AppProviders';
import type {
  AvailabilityChange,
  AvailabilityPoller,
  AvailabilityRun,
  SnapshotStats,
} from '@/api/availability-dashboard-api';
import { AvailabilityPage } from './AvailabilityPage';

/** Mirrors PollersTab's row-message TTL. */
const FEEDBACK_TTL_MS = 6000;

// Chart.js needs a real 2D context, which jsdom has none of. The data shaping it
// would draw is tested purely in ChangesChart.test.ts.
vi.mock('@/features/availability-dashboard/ChangesChart', () => ({
  ChangesChart: () => <div data-testid="changes-chart" />,
}));

const poller = (fields: Partial<AvailabilityPoller> = {}): AvailabilityPoller => ({
  id: 7,
  provider: 'recgov',
  parent_ref: '232447',
  poi_id: 42,
  active: true,
  next_run_at: '2026-07-08T14:30:00.123Z',
  claimed_until: null,
  last_run_at: '2026-07-08T14:00:00Z',
  attached_watches: 3,
  created_at: '2026-06-01T00:00:00Z',
  updated_at: '2026-06-01T00:00:00Z',
  ...fields,
});

const run = (fields: Partial<AvailabilityRun> = {}): AvailabilityRun => ({
  id: 100,
  poller_id: 7,
  status: 'completed',
  snapshot_count: 12,
  duration_ms: 850,
  error: null,
  started_at: '2026-07-08T14:00:00Z',
  completed_at: '2026-07-08T14:00:01Z',
  ...fields,
});

const change = (fields: Partial<AvailabilityChange> = {}): AvailabilityChange => ({
  campsite_id: 1,
  campsite_name: 'Loop A 001',
  target_date: '2026-07-08',
  observed_at: '2026-07-01T12:00:00Z',
  from_status: 'reserved',
  to_status: 'available',
  ...fields,
});

const stats = (fields: Partial<SnapshotStats> = {}): SnapshotStats => ({
  target_date: '2026-07-08',
  total_runs: 40,
  first_run_at: '2026-06-01T00:00:00Z',
  last_run_at: '2026-07-07T00:00:00Z',
  median_cadence_sec: 3600,
  last_open_at: '2026-07-07T21:00:00Z',
  is_currently_open: false,
  min_open_window_sec: 45,
  max_open_window_sec: 8100,
  ...fields,
});

// Fetch harness — responders claim a request by URL+method, first match wins.

interface Recorded {
  url: string;
  method: string;
}

interface Responder {
  match: (url: string, method: string) => boolean;
  respond: (url: string) => Response;
}

const requests: Recorded[] = [];
let responders: Responder[] = [];

const json = (body: unknown, status = 200): Response =>
  new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });

const startsWith = (prefix: string, respond: (url: string) => Response): Responder => ({
  match: (url, method) => url.startsWith(prefix) && method === 'GET',
  respond,
});

const pollerList = (...pollers: AvailabilityPoller[]) =>
  startsWith('/api/availability/pollers?', () =>
    json({ total: pollers.length, limit: 50, offset: 0, pollers }),
  );

const pollerSummary = (fields: Partial<Record<string, number>> = {}) =>
  startsWith('/api/availability/pollers/summary', () =>
    json({ active: 5, dormant: 2, due_now: 1, claimed: 0, ...fields }),
  );

const runList = (...runs: AvailabilityRun[]) =>
  startsWith('/api/availability/runs', () => json({ runs }));

const changeList = (...changes: AvailabilityChange[]) =>
  startsWith('/api/availability/changes?', () => json({ changes }));

const changesSummary = (...rows: SnapshotStats[]) =>
  startsWith('/api/availability/changes/summary', () => json({ poi_id: 42, stats: rows }));

function stubApi(...overrides: Responder[]) {
  responders = [
    ...overrides,
    pollerSummary(),
    pollerList(),
    runList(),
    changesSummary(),
    changeList(),
  ];
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: unknown, init?: RequestInit): Promise<Response> => {
      const url = String(input);
      const method = init?.method ?? 'GET';
      requests.push({ url, method });
      const hit = responders.find((r) => r.match(url, method));
      if (!hit) throw new Error(`unstubbed request: ${method} ${url}`);
      return hit.respond(url);
    }),
  );
}

const asked = (fragment: string): Recorded | undefined =>
  requests.find((r) => r.url.includes(fragment));

let client: QueryClient;

function renderPage(search = '') {
  window.history.replaceState(null, '', `/availability${search}`);
  return render(
    <AppProviders client={client}>
      <AvailabilityPage />
    </AppProviders>,
  );
}

beforeEach(() => {
  requests.length = 0;
  client = createTestQueryClient();
});

afterEach(() => {
  vi.unstubAllGlobals();
  client.clear();
});

describe('tab routing', () => {
  test('opens on the pollers tab', async () => {
    stubApi(pollerList(poller()));
    renderPage();

    expect(await screen.findByRole('link', { name: 'Pollers' })).toHaveAttribute(
      'aria-current',
      'page',
    );
  });

  test('opens on the tab named in the URL', async () => {
    stubApi(runList(run()));
    renderPage('?tab=runs');

    expect(await screen.findByRole('link', { name: 'Runs' })).toHaveAttribute(
      'aria-current',
      'page',
    );
  });

  test('the tabs are links carrying their own URL', async () => {
    stubApi(pollerList());
    renderPage();

    expect(await screen.findByRole('link', { name: 'Runs' })).toHaveAttribute(
      'href',
      '?tab=runs',
    );
    expect(screen.getByRole('link', { name: 'Changes' })).toHaveAttribute('href', '?tab=changes');
  });

  test('clicking a tab switches without leaving the page', async () => {
    stubApi(pollerList(), runList(run()));
    renderPage();
    await screen.findByRole('link', { name: 'Pollers' });

    await userEvent.click(screen.getByRole('link', { name: 'Runs' }));

    expect(await screen.findByLabelText('Poller ID')).toBeInTheDocument();
    expect(window.location.search).toBe('?tab=runs');
  });

  test('an unknown tab falls back to pollers', async () => {
    stubApi(pollerList());
    renderPage('?tab=nope');

    expect(await screen.findByRole('link', { name: 'Pollers' })).toHaveAttribute(
      'aria-current',
      'page',
    );
  });
});

describe('pollers tab', () => {
  test('shows the summary counters', async () => {
    stubApi(pollerSummary({ active: 5, dormant: 2, due_now: 1, claimed: 3 }));
    renderPage();

    const panel = (await screen.findByRole('heading', { name: 'Status' })).closest('section')!;

    // Matched as label+value pairs against the panel's text: each counter puts its
    // number in a nested <strong>, so no single element holds "active 5", and
    // asserting the label alone would not catch a value landing on the wrong one.
    await waitFor(() => expect(panel.textContent).toMatch(/active\s*5/));
    expect(panel.textContent).toMatch(/dormant\s*2/);
    expect(panel.textContent).toMatch(/due now\s*1/);
    expect(panel.textContent).toMatch(/claimed\s*3/);
  });

  test('requests the active filter by default', async () => {
    stubApi(pollerList(poller()));
    renderPage();

    await waitFor(() => expect(asked('active=true')).toBeTruthy());
  });

  test('renders a poller row with formatted timestamps', async () => {
    stubApi(pollerList(poller({ id: 7, provider: 'recgov', parent_ref: '232447' })));
    renderPage();

    const table = await screen.findByRole('table');
    expect(within(table).getByText('recgov')).toBeInTheDocument();
    expect(within(table).getByText('232447')).toBeInTheDocument();
    // Fraction and Z stripped, no timezone shift.
    expect(within(table).getByText('2026-07-08 14:30:00')).toBeInTheDocument();
  });

  test('a null last run and claim render as dashes', async () => {
    stubApi(pollerList(poller({ last_run_at: null, claimed_until: null })));
    renderPage();

    const table = await screen.findByRole('table');
    expect(within(table).getAllByText('—')).toHaveLength(2);
  });

  test('the count line and the empty state both appear when there are none', async () => {
    stubApi(pollerList());
    renderPage();

    expect(await screen.findByText('0 pollers.')).toBeInTheDocument();
    expect(screen.getByText('No pollers.')).toBeInTheDocument();
  });

  test('singular for exactly one poller', async () => {
    stubApi(pollerList(poller()));
    renderPage();

    expect(await screen.findByText('1 poller.')).toBeInTheDocument();
  });

  test('a list error is reported', async () => {
    stubApi(startsWith('/api/availability/pollers?', () => json({ error: 'boom' }, 500)));
    renderPage();

    expect(await screen.findByText(/^Error:/)).toBeInTheDocument();
  });

  test('a summary error does not hide the table', async () => {
    stubApi(
      startsWith('/api/availability/pollers/summary', () => json({ error: 'boom' }, 500)),
      pollerList(poller()),
    );
    renderPage();

    expect(await screen.findByText(/Counters error:/)).toBeInTheDocument();
    expect(await screen.findByRole('table')).toBeInTheDocument();
  });

  test("a poller's id links to its runs", async () => {
    stubApi(pollerList(poller({ id: 7 })), runList(run()));
    renderPage();

    const link = await screen.findByRole('link', { name: '7' });
    expect(link).toHaveAttribute('href', '?tab=runs&poller_id=7');

    await userEvent.click(link);

    // Landed on runs, already scoped to that poller.
    expect(await screen.findByLabelText('Poller ID')).toHaveValue('7');
    await waitFor(() => expect(asked('poller_id=7')).toBeTruthy());
  });

  test('applying a filter refetches with it', async () => {
    stubApi(pollerList(poller()));
    renderPage();
    await screen.findByRole('table');

    await userEvent.selectOptions(screen.getByLabelText('Active'), 'false');
    await userEvent.click(screen.getByRole('button', { name: 'Apply' }));

    await waitFor(() => expect(asked('active=false')).toBeTruthy());
  });
});

describe('check now', () => {
  const forceRoute = (respond: () => Response): Responder => ({
    match: (url, method) => url === '/api/availability/pollers/7/force' && method === 'POST',
    respond,
  });

  test('a queued check reports back and refetches the list', async () => {
    stubApi(
      pollerList(poller({ id: 7 })),
      forceRoute(() => json({ poller_id: 7, next_run_at: '2026-07-08T15:00:00Z' })),
    );
    renderPage();
    await screen.findByRole('table');
    const before = requests.filter((r) => r.url.includes('/pollers?')).length;

    await userEvent.click(screen.getByRole('button', { name: 'check now' }));

    expect(await screen.findByText('queued')).toBeInTheDocument();
    // next_run_at moved, so the row's scheduling columns are stale.
    await waitFor(() =>
      expect(requests.filter((r) => r.url.includes('/pollers?')).length).toBeGreaterThan(before),
    );
  });

  test('a cooldown reports the retry delay', async () => {
    stubApi(
      pollerList(poller({ id: 7 })),
      forceRoute(() => json({ poller_id: 7, retry_after_sec: 42 }, 429)),
    );
    renderPage();
    await screen.findByRole('table');

    await userEvent.click(screen.getByRole('button', { name: 'check now' }));

    expect(await screen.findByText('try again in 42s')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'check now' })).not.toBeDisabled();
  });

  test('a cooldown with no delay still says something', async () => {
    stubApi(
      pollerList(poller({ id: 7 })),
      forceRoute(() => json({ poller_id: 7 }, 429)),
    );
    renderPage();
    await screen.findByRole('table');

    await userEvent.click(screen.getByRole('button', { name: 'check now' }));

    expect(await screen.findByText('try again in ?s')).toBeInTheDocument();
  });

  test('a missing poller refetches the list instead of showing an error', async () => {
    stubApi(
      pollerList(poller({ id: 7 })),
      forceRoute(() => json({ error: 'gone' }, 404)),
    );
    renderPage();
    await screen.findByRole('table');
    const before = requests.filter((r) => r.url.includes('/pollers?')).length;

    await userEvent.click(screen.getByRole('button', { name: 'check now' }));

    await waitFor(() =>
      expect(requests.filter((r) => r.url.includes('/pollers?')).length).toBeGreaterThan(before),
    );
    expect(screen.queryByText(/^error/)).not.toBeInTheDocument();
  });

  test('another failure shows its status', async () => {
    stubApi(
      pollerList(poller({ id: 7 })),
      forceRoute(() => json({ error: 'boom' }, 500)),
    );
    renderPage();
    await screen.findByRole('table');

    await userEvent.click(screen.getByRole('button', { name: 'check now' }));

    expect(await screen.findByText('error (500)')).toBeInTheDocument();
  });

  test('the queued message clears once the refreshed list arrives', async () => {
    // shouldAdvanceTime keeps real time moving, so userEvent and waitFor still
    // work while the TTL timer stays under the test's control.
    vi.useFakeTimers({ shouldAdvanceTime: true });
    try {
      stubApi(
        pollerList(poller({ id: 7 })),
        forceRoute(() => json({ poller_id: 7, next_run_at: '2026-07-08T15:00:00Z' })),
      );
      renderPage();
      await screen.findByRole('table');

      await userEvent.click(screen.getByRole('button', { name: 'check now' }));
      await screen.findByText('queued');

      await act(async () => {
        vi.advanceTimersByTime(FEEDBACK_TTL_MS);
      });
      expect(screen.queryByText('queued')).not.toBeInTheDocument();
    } finally {
      vi.useRealTimers();
    }
  });

  test('a dormant poller cannot be checked', async () => {
    stubApi(pollerList(poller({ active: false })));
    renderPage();
    await screen.findByRole('table');

    expect(screen.getByRole('button', { name: 'check now' })).toBeDisabled();
  });
});

describe('runs tab', () => {
  test('renders a run row', async () => {
    stubApi(runList(run({ id: 100, poller_id: 7, duration_ms: 850 })));
    renderPage('?tab=runs');

    const table = await screen.findByRole('table');
    expect(within(table).getByText('850ms')).toBeInTheDocument();
    expect(within(table).getByText('completed')).toBeInTheDocument();
  });

  test('a null duration renders as a dash', async () => {
    stubApi(runList(run({ duration_ms: null })));
    renderPage('?tab=runs');

    const table = await screen.findByRole('table');
    expect(within(table).getByText('—')).toBeInTheDocument();
  });

  test('counts the rows returned', async () => {
    stubApi(runList(run({ id: 1 }), run({ id: 2 })));
    renderPage('?tab=runs');

    expect(await screen.findByText('2 runs.')).toBeInTheDocument();
  });

  test('a long error is truncated to the cell width', async () => {
    const longError = 'x'.repeat(200);
    stubApi(runList(run({ error: longError })));
    renderPage('?tab=runs');

    const table = await screen.findByRole('table');
    const cell = within(table).getByText(/x+…$/);
    expect(cell.textContent).toHaveLength(80);
  });

  test('seeds both filters from the URL', async () => {
    stubApi(runList(run()));
    renderPage('?tab=runs&poller_id=7&status=failed');

    expect(await screen.findByLabelText('Poller ID')).toHaveValue('7');
    expect(screen.getByLabelText('Status')).toHaveValue('failed');
    await waitFor(() => expect(asked('status=failed')).toBeTruthy());
  });

  test('applying a filter puts it in the URL so the view is linkable', async () => {
    stubApi(runList(run()));
    renderPage('?tab=runs');
    await screen.findByRole('table');

    await userEvent.type(screen.getByLabelText('Poller ID'), '9');
    await userEvent.click(screen.getByRole('button', { name: 'Apply' }));

    await waitFor(() => expect(window.location.search).toBe('?tab=runs&poller_id=9'));
  });

  test('reset clears the filter and the input', async () => {
    stubApi(runList(run()));
    renderPage('?tab=runs&poller_id=7');
    await screen.findByRole('table');

    await userEvent.click(screen.getByRole('button', { name: 'Reset' }));

    await waitFor(() => expect(screen.getByLabelText('Poller ID')).toHaveValue(''));
    expect(window.location.search).toBe('?tab=runs');
  });
});

describe('changes tab', () => {
  test('asks for a target before requesting anything', async () => {
    stubApi();
    renderPage('?tab=changes');

    expect(
      await screen.findByText('Set a Campsite ID or POI ID to load changes.'),
    ).toBeInTheDocument();
    expect(asked('/changes')).toBeUndefined();
  });

  test('refuses both a POI and a campsite', async () => {
    stubApi();
    renderPage('?tab=changes');

    await userEvent.type(screen.getByLabelText('POI ID'), '42');
    await userEvent.type(screen.getByLabelText('Campsite ID'), '1');
    await userEvent.click(screen.getByRole('button', { name: 'Apply' }));

    expect(
      await screen.findByText('Set exactly one of Campsite ID or POI ID.'),
    ).toBeInTheDocument();
    expect(asked('/changes')).toBeUndefined();
  });

  test('loads changes for a POI from the URL', async () => {
    stubApi(changeList(change()), changesSummary(stats()));
    renderPage('?tab=changes&poi_id=42');

    expect(await screen.findByText('1 change.')).toBeInTheDocument();
    expect(asked('/changes?poi_id=42')).toBeTruthy();
  });

  test('loads changes for a campsite and shows no stats panel', async () => {
    stubApi(changeList(change()));
    renderPage('?tab=changes&campsite_id=1');

    expect(await screen.findByText('1 change.')).toBeInTheDocument();
    // The summary endpoint is POI-scoped; a campsite view has none.
    expect(asked('/changes/summary')).toBeUndefined();
    expect(screen.queryByText('Stats')).not.toBeInTheDocument();
  });

  test('renders the stats table for a POI', async () => {
    stubApi(changeList(change()), changesSummary(stats()));
    renderPage('?tab=changes&poi_id=42');

    expect(await screen.findByText('Stats')).toBeInTheDocument();
    // 3600s cadence, and the weekday beside the target date.
    expect(screen.getByText('1h 0m')).toBeInTheDocument();
    expect(screen.getByText('Wed')).toBeInTheDocument();
  });

  test('the last-available column reads as time before the target date', async () => {
    stubApi(changeList(change()), changesSummary(stats({ last_open_at: '2026-07-07T21:00:00Z' })));
    renderPage('?tab=changes&poi_id=42');

    // 2026-07-08T00:00Z minus 2026-07-07T21:00Z = 3h.
    expect(await screen.findByText('3h before')).toBeInTheDocument();
  });

  test('a date never seen open reads as infinity', async () => {
    stubApi(changeList(change()), changesSummary(stats({ last_open_at: null })));
    renderPage('?tab=changes&poi_id=42');

    expect(await screen.findByText('∞')).toBeInTheDocument();
  });

  test('a failing stats summary leaves the change list alone', async () => {
    stubApi(
      changeList(change()),
      startsWith('/api/availability/changes/summary', () => json({ error: 'boom' }, 500)),
    );
    renderPage('?tab=changes&poi_id=42');

    expect(await screen.findByText('1 change.')).toBeInTheDocument();
    expect(screen.queryByText('Stats')).not.toBeInTheDocument();
  });

  test('the chart appears only when there are changes', async () => {
    stubApi(changeList(change()), changesSummary());
    renderPage('?tab=changes&poi_id=42');

    expect(await screen.findByTestId('changes-chart')).toBeInTheDocument();
  });

  test('no chart for an empty result', async () => {
    stubApi(changeList(), changesSummary());
    renderPage('?tab=changes&poi_id=42');

    expect(await screen.findByText('No changes.')).toBeInTheDocument();
    expect(screen.queryByTestId('changes-chart')).not.toBeInTheDocument();
  });

  test('a target date is passed through and kept in the URL', async () => {
    stubApi(changeList(change()), changesSummary(stats()));
    renderPage('?tab=changes&poi_id=42&target_date=2026-07-08');

    await waitFor(() => expect(asked('target_date=2026-07-08')).toBeTruthy());
    expect(screen.getByLabelText('Target Date')).toHaveValue('2026-07-08');
  });

  test('applying a filter rewrites the URL', async () => {
    stubApi(changeList(change()), changesSummary(stats()));
    renderPage('?tab=changes');

    await userEvent.type(screen.getByLabelText('POI ID'), '42');
    await userEvent.click(screen.getByRole('button', { name: 'Apply' }));

    await waitFor(() => expect(window.location.search).toBe('?tab=changes&poi_id=42'));
  });

  test('a change with no campsite name falls back to its id', async () => {
    stubApi(changeList(change({ campsite_name: null, campsite_id: 99 })), changesSummary());
    renderPage('?tab=changes&poi_id=42');

    const table = await screen.findByRole('table');
    expect(within(table).getByText('#99')).toBeInTheDocument();
  });

  test('a null from-status renders as a dash', async () => {
    stubApi(changeList(change({ from_status: null })), changesSummary());
    renderPage('?tab=changes&poi_id=42');

    const table = await screen.findByRole('table');
    expect(within(table).getByText('—')).toBeInTheDocument();
  });
});
