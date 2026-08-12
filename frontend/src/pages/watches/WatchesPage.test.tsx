import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest';
import type { QueryClient } from '@tanstack/react-query';
import { createTestQueryClient } from '@/test/query-client';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { Watch } from '@/api/watches-api';
import { AppProviders } from '@/app/AppProviders';
import { queryKeys } from '@/queries/keys';
import { WatchesPage } from './WatchesPage';

const watch = (fields: Partial<Watch> = {}): Watch => ({
  id: 1,
  targets: [{ poi_id: 42 }],
  poi_id: 42,
  campsite_filters: {},
  start_date: '2026-07-08',
  end_date: '2026-07-15',
  trigger_kinds: ['slack_notify'],
  trigger_config: { slack_notify: { channel: '#alerts' } },
  stop_when_triggered: true,
  status: 'active',
  created_at: '2026-06-01T00:00:00Z',
  updated_at: '2026-06-01T00:00:00Z',
  ...fields,
});

// Fetch harness. Responders claim a request by URL+method; first match wins.

interface Recorded {
  url: string;
  method: string;
  body: unknown;
}

interface Responder {
  match: (url: string, method: string) => boolean;
  respond: (url: string) => Response;
}

const requests: Recorded[] = [];
let responders: Responder[] = [];

const json = (body: unknown, status = 200): Response =>
  new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });

/** A raw text body, for the create/update error path that reads `.body`. */
const textBody = (body: string, status: number): Response => new Response(body, { status });

const noContent = (): Response => new Response(null, { status: 204 });

/**
 * The three status queries, answered from one set of watches.
 *
 * Filters by the requested status the way the route does. Returning every watch
 * for every status would triple each row, which is not a shape the backend can
 * produce.
 */
function watchList(...watches: Watch[]): Responder {
  return {
    match: (url, method) => url.startsWith('/api/watches?') && method === 'GET',
    respond: (url) => {
      const status = new URL(url, 'http://test').searchParams.get('status');
      const matching = watches.filter((w) => w.status === status);
      return json({ total: matching.length, limit: 200, offset: 0, watches: matching });
    },
  };
}

/** The list route failing on every status. */
function watchListFails(status: number, body: unknown = {}): Responder {
  return {
    match: (url, method) => url.startsWith('/api/watches?') && method === 'GET',
    respond: () => json(body, status),
  };
}

/**
 * A list whose session can die: answers normally until `expired()` goes true,
 * then 401s. Delegates to `watchList` so the per-status filtering stays in one
 * place — a responder that ignores `status` triples every row.
 */
function watchListUntilExpired(expired: () => boolean, ...watches: Watch[]): Responder {
  const live = watchList(...watches);
  return {
    match: live.match,
    respond: (url) => (expired() ? json({ error: 'unauthenticated' }, 401) : live.respond(url)),
  };
}

const route = (url: string, method: string, respond: () => Response): Responder => ({
  match: (u, m) => u === url && m === method,
  respond,
});

function stubApi(...overrides: Responder[]) {
  responders = [
    ...overrides,
    // Any POI detail resolves to a name, so the table's POI cell has one.
    {
      match: (url) => url.startsWith('/api/pois/'),
      respond: () => json({ properties: { name: 'Manzanita Lake' } }),
    },
    // Default: every status list is empty.
    watchList(),
  ];
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: unknown, init?: RequestInit): Promise<Response> => {
      const url = String(input);
      const method = init?.method ?? 'GET';
      const raw = init?.body;
      requests.push({
        url,
        method,
        body: typeof raw === 'string' ? (JSON.parse(raw) as unknown) : undefined,
      });
      const hit = responders.find((r) => r.match(url, method));
      if (!hit) throw new Error(`unstubbed request: ${method} ${url}`);
      return hit.respond(url);
    }),
  );
}

const postedTo = (url: string): Recorded | undefined =>
  requests.find((r) => r.url === url && r.method === 'POST');

const GET_WATCH_7 = '/api/watches/7';
const MODIFY_WATCH_7 = '/api/watches/7/modify';
const DELETE_WATCH_7 = '/api/watches/7/delete';

let client: QueryClient;

function renderPage() {
  return render(
    <AppProviders client={client}>
      <WatchesPage />
    </AppProviders>,
  );
}

beforeEach(() => {
  requests.length = 0;
  window.history.replaceState(null, '', '/watches.html');
  client = createTestQueryClient();
});

afterEach(() => {
  vi.unstubAllGlobals();
  client.clear();
});

describe('loading', () => {
  test('requests one list per status, at the legacy limit', async () => {
    stubApi(watchList());
    renderPage();

    await waitFor(() => {
      expect(requests.filter((r) => r.url.startsWith('/api/watches?'))).toHaveLength(3);
    });
    expect(
      requests
        .filter((r) => r.url.startsWith('/api/watches?'))
        .map((r) => r.url)
        .sort(),
    ).toEqual([
      '/api/watches?status=active&limit=200',
      '/api/watches?status=done&limit=200',
      '/api/watches?status=paused&limit=200',
    ]);
  });

  test('merges the three status lists into one table', async () => {
    stubApi(
      watchList(
        watch({ id: 1, status: 'active' }),
        watch({ id: 2, status: 'paused' }),
        watch({ id: 3, status: 'done' }),
      ),
    );
    renderPage();

    await screen.findByRole('table');
    expect(screen.getAllByRole('row')).toHaveLength(4); // header + 3
    expect(screen.getByText('Active')).toBeInTheDocument();
    expect(screen.getByText('Paused')).toBeInTheDocument();
    expect(screen.getByText('Done')).toBeInTheDocument();
  });

  test('resolves POI names for the rows', async () => {
    stubApi(watchList(watch({ id: 7 })));
    renderPage();

    expect(await screen.findByRole('link', { name: 'Manzanita Lake' })).toHaveAttribute(
      'href',
      '/?poi=42',
    );
  });

  test('shows the empty state when there are no watches', async () => {
    stubApi(watchList());
    renderPage();

    expect(await screen.findByText('No watches yet')).toBeInTheDocument();
  });
});

describe('signed out', () => {
  test('a 401 prompts for sign-in and hides the form', async () => {
    stubApi(watchListFails(401, { error: 'unauthenticated' }));
    renderPage();

    expect(await screen.findByText('Sign in to manage your alerts')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Create' })).not.toBeInTheDocument();
  });

  test('a 500 shows an error, not a sign-in prompt', async () => {
    stubApi(watchListFails(500, { error: 'boom' }));
    renderPage();

    expect(await screen.findByText('Could not load watches.')).toBeInTheDocument();
    expect(screen.queryByText('Sign in to manage your alerts')).not.toBeInTheDocument();
  });
});

describe('create', () => {
  const createOk = () => route('/api/watches', 'POST', () => json({ watch: watch() }, 201));

  test('posts the typed fields and the default triggers', async () => {
    stubApi(watchList(), createOk());
    renderPage();
    await screen.findByText('No watches yet');

    await userEvent.type(screen.getByLabelText('POI ID'), '42');
    await userEvent.click(screen.getByRole('button', { name: 'Create' }));

    await waitFor(() => expect(postedTo('/api/watches')).toBeTruthy());
    expect(postedTo('/api/watches')!.body).toMatchObject({
      poi_id: 42,
      trigger_kinds: ['slack_notify'],
      stop_when_triggered: true,
    });
  });

  test('confirms with a banner and resets the form', async () => {
    stubApi(watchList(), createOk());
    renderPage();
    await screen.findByText('No watches yet');

    await userEvent.type(screen.getByLabelText('POI ID'), '42');
    await userEvent.click(screen.getByRole('button', { name: 'Create' }));

    expect(await screen.findByText('Watch created for POI 42.')).toBeInTheDocument();
    expect(screen.getByLabelText('POI ID')).toHaveValue('');
  });

  test('sends the email trigger once it is switched on', async () => {
    stubApi(watchList(), createOk());
    renderPage();
    await screen.findByText('No watches yet');

    await userEvent.click(screen.getByRole('checkbox', { name: 'Email' }));
    await userEvent.click(screen.getByRole('button', { name: 'Create' }));

    await waitFor(() => expect(postedTo('/api/watches')).toBeTruthy());
    expect(postedTo('/api/watches')!.body).toMatchObject({
      trigger_kinds: ['slack_notify', 'email_notify'],
      trigger_config: {},
    });
  });

  test('email never renders a watch-level recipient field', async () => {
    stubApi(watchList(), createOk());
    renderPage();
    await screen.findByText('No watches yet');

    await userEvent.click(screen.getByRole('checkbox', { name: 'Email' }));
    expect(screen.queryByLabelText('Email address')).toBeNull();
  });

  test('surfaces the backend validation detail in the form', async () => {
    stubApi(
      watchList(),
      route('/api/watches', 'POST', () => textBody('start_date must be before end_date', 400)),
    );
    renderPage();
    await screen.findByText('No watches yet');

    await userEvent.click(screen.getByRole('button', { name: 'Create' }));

    expect(await screen.findByText('start_date must be before end_date')).toBeInTheDocument();
  });

});

describe('edit', () => {
  const listWith7 = (fields: Partial<Watch> = {}) => watchList(watch({ id: 7, ...fields }));
  const getWatch7 = (fields: Partial<Watch> = {}) =>
    route(GET_WATCH_7, 'GET', () => json({ watch: watch({ id: 7, ...fields }) }));
  const modifyOk = () => route(MODIFY_WATCH_7, 'POST', () => json({ watch: watch({ id: 7 }) }));

  async function openEditor() {
    renderPage();
    await userEvent.click(await screen.findByRole('button', { name: 'Edit' }));
    await screen.findByText('Edit Watch');
  }

  test('locks the target and the window', async () => {
    stubApi(listWith7(), getWatch7());
    await openEditor();

    expect(screen.getByLabelText('POI ID')).toBeDisabled();
    expect(screen.getByLabelText('Start date')).toBeDisabled();
    expect(screen.getByLabelText('End date')).toBeDisabled();
  });

  test('prefills the fields and the trigger state from the watch', async () => {
    stubApi(listWith7(), getWatch7());
    await openEditor();

    expect(screen.getByLabelText('POI ID')).toHaveValue('42');
    expect(screen.getByLabelText('Start date')).toHaveValue('2026-07-08');
    expect(screen.getByLabelText('Channel')).toHaveValue('#alerts');
    expect(screen.getByRole('checkbox', { name: 'Slack' })).toBeChecked();
    expect(screen.getByRole('checkbox', { name: 'Email' })).not.toBeChecked();
  });

  test('saves through the /modify route and reports it', async () => {
    stubApi(listWith7(), getWatch7(), modifyOk());
    await openEditor();

    await userEvent.click(screen.getByRole('button', { name: 'Save' }));

    expect(await screen.findByText('Watch #7 updated.')).toBeInTheDocument();
    expect(postedTo(MODIFY_WATCH_7)!.body).toMatchObject({
      start_date: '2026-07-08',
      end_date: '2026-07-15',
      trigger_kinds: ['slack_notify'],
    });
  });

  test('reactivates a done watch on save', async () => {
    stubApi(listWith7({ status: 'done' }), getWatch7({ status: 'done' }), modifyOk());
    await openEditor();

    await userEvent.click(screen.getByRole('button', { name: 'Save' }));

    expect(await screen.findByText('Watch #7 reactivated.')).toBeInTheDocument();
    expect(postedTo(MODIFY_WATCH_7)!.body).toMatchObject({ status: 'active' });
  });

  test('an ordinary save does not change the status', async () => {
    stubApi(listWith7(), getWatch7(), modifyOk());
    await openEditor();

    await userEvent.click(screen.getByRole('button', { name: 'Save' }));

    await waitFor(() => expect(postedTo(MODIFY_WATCH_7)).toBeTruthy());
    expect(postedTo(MODIFY_WATCH_7)!.body).not.toHaveProperty('status');
  });

  test('an edited channel survives its toggle being switched off and on', async () => {
    stubApi(listWith7(), getWatch7(), modifyOk());
    await openEditor();
    expect(screen.getByLabelText('Channel')).toHaveValue('#alerts');

    await userEvent.clear(screen.getByLabelText('Channel'));
    await userEvent.type(screen.getByLabelText('Channel'), '#new');
    await userEvent.click(screen.getByRole('checkbox', { name: 'Slack' }));
    await userEvent.click(screen.getByRole('checkbox', { name: 'Slack' }));

    expect(screen.getByLabelText('Channel')).toHaveValue('#new');

    await userEvent.click(screen.getByRole('button', { name: 'Save' }));

    await waitFor(() => expect(postedTo(MODIFY_WATCH_7)).toBeTruthy());
    expect(postedTo(MODIFY_WATCH_7)!.body).toMatchObject({
      trigger_config: { slack_notify: { channel: '#new' } },
    });
  });

  test('cancel returns to the create form', async () => {
    stubApi(listWith7(), getWatch7());
    await openEditor();

    await userEvent.click(screen.getByRole('button', { name: 'Cancel' }));

    expect(await screen.findByText('Create Watch')).toBeInTheDocument();
    expect(screen.getByLabelText('POI ID')).not.toBeDisabled();
  });

  test('reports a failed load without opening the editor', async () => {
    stubApi(listWith7(), route(GET_WATCH_7, 'GET', () => json({ error: 'boom' }, 500)));
    renderPage();

    await userEvent.click(await screen.findByRole('button', { name: 'Edit' }));

    expect(await screen.findByText('Could not load watch for editing.')).toBeInTheDocument();
    expect(screen.getByText('Create Watch')).toBeInTheDocument();
  });
});

describe('pause and delete', () => {
  test('pausing posts the new status', async () => {
    stubApi(
      watchList(watch({ id: 7, status: 'active' })),
      route(MODIFY_WATCH_7, 'POST', () => json({ watch: watch({ id: 7, status: 'paused' }) })),
    );
    renderPage();

    await userEvent.click(await screen.findByRole('button', { name: 'Pause' }));

    await waitFor(() => expect(postedTo(MODIFY_WATCH_7)).toBeTruthy());
    expect(postedTo(MODIFY_WATCH_7)!.body).toEqual({ status: 'paused' });
  });

  test('resuming a paused watch posts active', async () => {
    stubApi(
      watchList(watch({ id: 7, status: 'paused' })),
      route(MODIFY_WATCH_7, 'POST', () => json({ watch: watch({ id: 7 }) })),
    );
    renderPage();

    await userEvent.click(await screen.findByRole('button', { name: 'Resume' }));

    await waitFor(() => expect(postedTo(MODIFY_WATCH_7)).toBeTruthy());
    expect(postedTo(MODIFY_WATCH_7)!.body).toEqual({ status: 'active' });
  });

  test('deleting takes two clicks and reports success', async () => {
    stubApi(watchList(watch({ id: 7 })), route(DELETE_WATCH_7, 'POST', noContent));
    renderPage();

    await userEvent.click(await screen.findByRole('button', { name: 'Delete' }));
    expect(postedTo(DELETE_WATCH_7)).toBeUndefined();

    await userEvent.click(screen.getByRole('button', { name: 'Confirm delete' }));

    expect(await screen.findByText('Watch #7 deleted.')).toBeInTheDocument();
  });

  test('a 401 on delete surfaces the sign-in prompt instead of failing silently', async () => {
    let sessionValid = true;
    stubApi(
      watchListUntilExpired(() => !sessionValid, watch({ id: 7 })),
      route(DELETE_WATCH_7, 'POST', () => {
        sessionValid = false;
        return json({ error: 'unauthenticated' }, 401);
      }),
    );
    renderPage();

    await userEvent.click(await screen.findByRole('button', { name: 'Delete' }));
    await userEvent.click(screen.getByRole('button', { name: 'Confirm delete' }));

    expect(await screen.findByText('Sign in to manage your alerts')).toBeInTheDocument();
  });

  test('a 401 on pause surfaces the sign-in prompt', async () => {
    let sessionValid = true;
    stubApi(
      watchListUntilExpired(() => !sessionValid, watch({ id: 7 })),
      route(MODIFY_WATCH_7, 'POST', () => {
        sessionValid = false;
        return json({ error: 'unauthenticated' }, 401);
      }),
    );
    renderPage();

    await userEvent.click(await screen.findByRole('button', { name: 'Pause' }));

    expect(await screen.findByText('Sign in to manage your alerts')).toBeInTheDocument();
  });

  test('reports a failed delete', async () => {
    stubApi(
      watchList(watch({ id: 7 })),
      route(DELETE_WATCH_7, 'POST', () => json({ error: 'boom' }, 500)),
    );
    renderPage();

    await userEvent.click(await screen.findByRole('button', { name: 'Delete' }));
    await userEvent.click(screen.getByRole('button', { name: 'Confirm delete' }));

    expect(await screen.findByText('Could not delete watch.')).toBeInTheDocument();
  });

  test('deleting the watch under edit falls back to create', async () => {
    stubApi(
      watchList(watch({ id: 7 })),
      route(GET_WATCH_7, 'GET', () => json({ watch: watch({ id: 7 }) })),
      route(DELETE_WATCH_7, 'POST', noContent),
    );
    renderPage();
    await userEvent.click(await screen.findByRole('button', { name: 'Edit' }));
    await screen.findByText('Edit Watch');

    await userEvent.click(screen.getByRole('button', { name: 'Delete' }));
    await userEvent.click(screen.getByRole('button', { name: 'Confirm delete' }));

    expect(await screen.findByText('Create Watch')).toBeInTheDocument();
  });
});

describe('deep links', () => {
  test('?action=create prefills the form and clears the query string', async () => {
    window.history.replaceState(
      null,
      '',
      '/watches.html?action=create&poi_id=99&start_date=2026-09-01',
    );
    stubApi(watchList());
    renderPage();

    await waitFor(() => expect(screen.getByLabelText('POI ID')).toHaveValue('99'));
    expect(screen.getByLabelText('Start date')).toHaveValue('2026-09-01');
    // Cleared so a refresh does not re-fire the action.
    expect(window.location.search).toBe('');
  });

  test('?action=modify opens the editor', async () => {
    window.history.replaceState(null, '', '/watches.html?action=modify&id=7');
    stubApi(
      watchList(watch({ id: 7 })),
      route(GET_WATCH_7, 'GET', () => json({ watch: watch({ id: 7 }) })),
    );
    renderPage();

    expect(await screen.findByText('Edit Watch')).toBeInTheDocument();
    expect(window.location.search).toBe('');
  });

  test('?action=delete deletes without a confirm step', async () => {
    window.history.replaceState(null, '', '/watches.html?action=delete&id=7');
    stubApi(watchList(watch({ id: 7 })), route(DELETE_WATCH_7, 'POST', noContent));
    renderPage();

    expect(await screen.findByText('Watch #7 deleted.')).toBeInTheDocument();
  });

  test('a deep-linked delete does not run when the list failed to load', async () => {
    window.history.replaceState(null, '', '/watches.html?action=delete&id=7');
    stubApi(watchListFails(500, { error: 'boom' }), route(DELETE_WATCH_7, 'POST', noContent));
    renderPage();

    await screen.findByText('Could not load watches.');
    expect(requests.find((r) => r.url.includes('/delete'))).toBeUndefined();
  });

  test('a deep-linked delete does not fire when a later retry succeeds', async () => {
    window.history.replaceState(null, '', '/watches.html?action=delete&id=7');
    let failing = true;
    stubApi(
      {
        match: (url, method) => url.startsWith('/api/watches?') && method === 'GET',
        respond: (url) =>
          failing
            ? json({ error: 'boom' }, 500)
            : watchList(watch({ id: 7 })).respond(url),
      },
      route(DELETE_WATCH_7, 'POST', noContent),
    );
    renderPage();
    await screen.findByText('Could not load watches.');

    failing = false;
    await userEvent.click(screen.getByRole('button', { name: 'Retry' }));

    // The table proves the retry succeeded; the action must still be gone.
    await screen.findByRole('table');
    expect(requests.find((r) => r.url.includes('/delete'))).toBeUndefined();
  });

  test('a deep-linked delete does not run when signed out', async () => {
    window.history.replaceState(null, '', '/watches.html?action=delete&id=7');
    stubApi(watchListFails(401, { error: 'unauthenticated' }));
    renderPage();

    await screen.findByText('Sign in to manage your alerts');
    expect(requests.find((r) => r.url.includes('/delete'))).toBeUndefined();
  });

  test('a dropped deep-linked delete does not fire after signing in later', async () => {
    window.history.replaceState(null, '', '/watches.html?action=delete&id=7');
    let signedIn = false;
    stubApi(
      watchListUntilExpired(() => !signedIn, watch({ id: 7 })),
      route(DELETE_WATCH_7, 'POST', noContent),
    );
    renderPage();
    await screen.findByText('Sign in to manage your alerts');

    signedIn = true;
    await client.invalidateQueries({ queryKey: queryKeys.watches.all() });

    // The table appears, proving the lists refetched and we are signed in now.
    await screen.findByRole('table');
    expect(requests.find((r) => r.url.includes('/delete'))).toBeUndefined();
  });

  test('a URL with no action leaves the form empty', async () => {
    stubApi(watchList());
    renderPage();
    await screen.findByText('No watches yet');

    expect(screen.getByLabelText('POI ID')).toHaveValue('');
  });
});

describe('notice banner', () => {
  test('can be dismissed', async () => {
    stubApi(watchList(), route('/api/watches', 'POST', () => json({ watch: watch() }, 201)));
    renderPage();
    await screen.findByText('No watches yet');
    await userEvent.type(screen.getByLabelText('POI ID'), '42');
    await userEvent.click(screen.getByRole('button', { name: 'Create' }));
    await screen.findByText('Watch created for POI 42.');

    await userEvent.click(screen.getByRole('button', { name: /dismiss/i }));

    await waitFor(() => {
      expect(screen.queryByText('Watch created for POI 42.')).not.toBeInTheDocument();
    });
  });
});
