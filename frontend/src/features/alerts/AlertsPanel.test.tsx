// The alerts panel, driven through its real hooks against stubbed endpoints.
//
// What matters here is the wiring the vanilla hand-rolled: that the panel is absent
// until there is something to show, that a 401 hides it entirely, that a Slack deep
// link focuses a row without acting on it, and that pause/resume/delete go through and
// refetch.
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest';
import { QueryClient } from '@tanstack/react-query';
import { act, render, screen, waitFor } from '@testing-library/react';
import { AppProviders } from '@/app/AppProviders';
import { useMapStore } from '@/stores/mapStore';
import { AlertsPanel } from './AlertsPanel';

const POI_ID = 232447;

const watch = (over: Record<string, unknown> = {}) => ({
  id: 9,
  poi_id: POI_ID,
  campsite_filters: {},
  start_date: '2026-08-10',
  end_date: '2026-08-11',
  trigger_kinds: ['slack_notify'],
  trigger_config: {},
  stop_when_triggered: true,
  status: 'active',
  created_at: '2026-08-01T00:00:00Z',
  updated_at: '2026-08-01T00:00:00Z',
  ...over,
});

let byStatus: Record<string, unknown[]>;
let listStatus: number;
let requests: { url: string; method: string; body: string }[];

const json = (body: unknown, status = 200): Response =>
  new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });

beforeEach(() => {
  requests = [];
  listStatus = 200;
  byStatus = { active: [watch()], paused: [], done: [] };
  useMapStore.setState({ selectedPoiId: null });
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      requests.push({ url, method: init?.method ?? 'GET', body: String(init?.body ?? '') });
      if (url.startsWith('/api/watches?')) {
        if (listStatus !== 200) return json({ error: 'unauthorized' }, listStatus);
        const status = new URL(url, 'http://localhost').searchParams.get('status') ?? 'active';
        const watches = byStatus[status] ?? [];
        return json({ watches, total: watches.length });
      }
      // The API is POST-to-an-action, not REST verbs: `/modify` and `/delete`.
      if (/\/api\/watches\/\d+\/(modify|delete)$/.test(url)) return json({ watch: watch() });
      if (/\/api\/watches\/\d+$/.test(url)) {
        return json({ watch: watch(), watch_capabilities: { trigger_kinds: ['slack_notify'] } });
      }
      if (url.startsWith(`/api/pois/${POI_ID}`)) {
        return json({ type: 'Feature', id: POI_ID, properties: { name: 'Bowman Bay' } });
      }
      return json({}, 404);
    }),
  );
});

afterEach(() => {
  vi.unstubAllGlobals();
  window.history.replaceState(null, '', '/');
});

const mount = () =>
  render(
    <AppProviders client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <AlertsPanel />
    </AppProviders>,
  );

const expand = async () => {
  const bar = await waitFor(() => screen.getByRole('button', { expanded: false }));
  await act(async () => bar.click());
};

describe('the bar', () => {
  test('names the watches, and the paused and done among them', async () => {
    byStatus = { active: [watch()], paused: [watch({ id: 8, status: 'paused' })], done: [] };
    mount();

    await waitFor(() =>
      expect(screen.getByText('2 availability alerts · 1 paused')).toBeInTheDocument(),
    );
  });

  // The panel is additive: it must not take space to say there is nothing.
  test('is absent for a user with no watches', async () => {
    byStatus = { active: [], paused: [], done: [] };
    const { container } = mount();

    await waitFor(() => expect(fetch).toHaveBeenCalled());
    expect(container.querySelector('#tb-alerts')).toBeNull();
  });

  // A 401 is the answer for an anonymous visitor, not a fault — and a nav row that says
  // "unauthorized" is worse than no nav row.
  test('is absent entirely when signed out', async () => {
    listStatus = 401;
    const { container } = mount();

    await waitFor(() => expect(fetch).toHaveBeenCalled());
    expect(container.querySelector('#tb-alerts')).toBeNull();
  });
});

describe('the table', () => {
  test('shows a row per watch, with its POI name', async () => {
    mount();
    await expand();

    await waitFor(() => expect(screen.getByText('Bowman Bay')).toBeInTheDocument());
    expect(screen.getByText('Aug 10')).toBeInTheDocument();
    expect(screen.getByRole('img', { name: 'Slack' })).toBeInTheDocument();
  });

  test('a never-checked watch shows a dash rather than an empty cell', async () => {
    mount();
    await expand();

    await waitFor(() => expect(screen.getByText('Bowman Bay')).toBeInTheDocument());
    expect(document.querySelector('.tb-alerts-faint')).not.toBeNull();
  });

  test('a failed run says so, with the error in its title', async () => {
    byStatus = {
      active: [watch({ last_run_status: 'failed', last_run_error: 'provider 500' })],
      paused: [],
      done: [],
    };
    mount();
    await expand();

    const error = await waitFor(() => screen.getByText('⚠ error'));
    expect(error).toHaveAttribute('title', 'provider 500');
  });

  // A done watch is terminal, so it gets a status glyph rather than a toggle — and
  // which glyph is inferred from its window, since the list cannot say why it ended.
  test('a done watch shows why it ended, and no toggle', async () => {
    byStatus = { active: [], paused: [], done: [watch({ status: 'done', end_date: '2020-01-01' })] };
    mount();
    await expand();

    await waitFor(() =>
      expect(screen.getByTitle('Watch window ended without availability')).toBeInTheDocument(),
    );
    expect(screen.queryByLabelText('Pause watch')).toBeNull();
    // Delete stays: a finished watch is still the user's to clear.
    expect(screen.getByLabelText('Delete watch')).toBeInTheDocument();
  });

  test('clicking a row opens its POI', async () => {
    mount();
    await expand();
    await waitFor(() => expect(screen.getByText('Bowman Bay')).toBeInTheDocument());

    await act(async () => {
      screen.getByText('Bowman Bay').closest('.tb-alerts-row')!.dispatchEvent(
        new MouseEvent('click', { bubbles: true }),
      );
    });

    expect(useMapStore.getState().selectedPoiId).toBe(POI_ID);
  });
});

describe('the row actions', () => {
  test('pausing sends the status and refetches', async () => {
    mount();
    await expand();
    await waitFor(() => expect(screen.getByLabelText('Pause watch')).toBeInTheDocument());
    const listsBefore = requests.filter((r) => r.url.startsWith('/api/watches?')).length;

    await act(async () => {
      screen.getByLabelText('Pause watch').click();
    });

    const modify = requests.find((r) => r.url.endsWith('/modify'));
    expect(modify).toBeDefined();
    expect(modify!.method).toBe('POST');
    expect(JSON.parse(modify!.body)).toEqual({ status: 'paused' });
    await waitFor(() =>
      expect(requests.filter((r) => r.url.startsWith('/api/watches?')).length).toBeGreaterThan(
        listsBefore,
      ),
    );
  });

  test('a paused watch offers resume instead', async () => {
    byStatus = { active: [], paused: [watch({ status: 'paused' })], done: [] };
    mount();
    await expand();

    await waitFor(() => expect(screen.getByLabelText('Resume watch')).toBeInTheDocument());
    await act(async () => {
      screen.getByLabelText('Resume watch').click();
    });

    expect(JSON.parse(requests.find((r) => r.url.endsWith('/modify'))!.body)).toEqual({
      status: 'active',
    });
  });

  test('deleting posts to the delete action', async () => {
    mount();
    await expand();
    await waitFor(() => expect(screen.getByLabelText('Delete watch')).toBeInTheDocument());

    await act(async () => {
      screen.getByLabelText('Delete watch').click();
    });

    expect(requests.some((r) => r.url.endsWith('/delete') && r.method === 'POST')).toBe(true);
  });

  // The row itself opens the drawer, so a control that did not stop its click would
  // pause the watch AND fly the map away from what the user was looking at.
  test('an action does not also open the POI', async () => {
    mount();
    await expand();
    await waitFor(() => expect(screen.getByLabelText('Pause watch')).toBeInTheDocument());

    await act(async () => {
      screen.getByLabelText('Pause watch').click();
    });

    expect(useMapStore.getState().selectedPoiId).toBeNull();
  });

  // The editor is 4d's `WatchEditor` — the second consumer that port was written for.
  test('editing opens the watch editor for that row', async () => {
    mount();
    await expand();
    await waitFor(() => expect(screen.getByLabelText('Edit watch')).toBeInTheDocument());

    await act(async () => {
      screen.getByLabelText('Edit watch').click();
    });

    expect(
      await screen.findByRole('group', { name: 'Availability watch editor' }),
    ).toBeInTheDocument();
    // Fetched by id, because only the detail response carries the capability block the
    // editor gates its triggers on.
    expect(requests.some((r) => /\/api\/watches\/9$/.test(r.url) && r.method === 'GET')).toBe(true);
  });
});

describe('a Slack deep link', () => {
  test('expands the panel and focuses the row it names', async () => {
    window.history.replaceState(null, '', '/?alert=9&alert_action=pause');
    mount();

    await waitFor(() => expect(screen.getByText('Bowman Bay')).toBeInTheDocument());
    expect(document.querySelector('.tb-alerts-row.is-focus')).not.toBeNull();
    // The named control is pulsed, not pressed: a stale or forwarded card must not be
    // able to change a watch, so the user finishes the action themselves.
    expect(screen.getByLabelText('Pause watch').className).toContain('is-armed');
    expect(requests.some((r) => r.url.endsWith('/modify'))).toBe(false);
  });

  // Stripped so a refresh or a back-nav does not re-focus, and only these two
  // parameters — a shared route or an open drawer may be in the URL too.
  test('strips its own parameters and leaves the others', async () => {
    window.history.replaceState(null, '', '/?alert=9&alert_action=pause&route=abc');
    mount();

    await waitFor(() => expect(window.location.search).toBe('?route=abc'));
  });

  // Following a Slack card while signed out should offer a way in, not an empty nav.
  test('offers sign-in when the user is signed out', async () => {
    listStatus = 401;
    window.history.replaceState(null, '', '/?alert=9');
    mount();

    await waitFor(() => expect(screen.getByText('Sign in to view this alert')).toBeInTheDocument());
    expect(screen.getByRole('button', { name: 'Sign in' })).toBeInTheDocument();
  });
});
