import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest';
import type { QueryClient } from '@tanstack/react-query';
import { createTestQueryClient } from '@/test/query-client';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { AppProviders } from '@/app/AppProviders';
import type { SettingsResponse } from '@/api/account-api';
import { useThemeStore } from '@/stores/themeStore';
import { SettingsModal } from './SettingsModal';

const settingsBody = (
  over: Partial<SettingsResponse['notifications']> = {},
  booking: Partial<SettingsResponse['booking']> = {},
): SettingsResponse => ({
  profile: {
    display_name: 'Ada',
    login_email: 'ada@example.test',
    is_email_verified: true,
    roles: [],
    provider_label: 'Clerk',
    theme: 'system',
  },
  notifications: {
    notification_email: null,
    slack_channel: '#alerts',
    slack_configured: false,
    slack_token_hint: null,
    ...over,
  },
  booking: {
    recgov_configured: false,
    recgov_username: null,
    ...booking,
  },
});

const RECGOV_URL = '/api/settings/recgov';
const RECGOV_STATUS_URL = '/api/settings/recgov/status';

const CONFIGURED_BOOKING = {
  recgov_configured: true,
  recgov_username: 'ada@example.test',
};

const profile: SettingsResponse['profile'] = settingsBody().profile;

interface Recorded {
  url: string;
  method: string;
  body: unknown;
}

const requests: Recorded[] = [];
let getSettings: () => Response;
let getRecgovStatus: () => Response;
let onPut: (url: string, body: unknown) => Response;

const json = (body: unknown, status = 200): Response =>
  new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });

function stubApi() {
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: unknown, init?: RequestInit): Promise<Response> => {
      const url = String(input);
      const method = init?.method ?? 'GET';
      const raw = init?.body;
      const body = typeof raw === 'string' ? (JSON.parse(raw) as unknown) : undefined;
      requests.push({ url, method, body });
      if (url === '/api/settings' && method === 'GET') return getSettings();
      if (url === RECGOV_STATUS_URL && method === 'GET') return getRecgovStatus();
      if (method === 'PUT' || method === 'POST' || method === 'DELETE') return onPut(url, body);
      throw new Error(`unstubbed ${method} ${url}`);
    }),
  );
}

const putTo = (url: string): Recorded | undefined =>
  requests.find((r) => r.url === url && r.method === 'PUT');

let client: QueryClient;

interface RenderOptions {
  onClose?: () => void;
  /**
   * Seeds the fetched document's profile and, unlike the module-level
   * `getSettings`/`onPut` stubs, keeps GET and PUT in sync with each other: a
   * PUT to `/api/settings/profile` updates the same document GET serves back.
   * The theme-preview tests below save a theme and then need the reload the
   * save triggers to echo it, not the fixed default.
   */
  profile?: SettingsResponse['profile'];
}

function renderSettingsModal({ onClose = vi.fn(), profile: profileOverride }: RenderOptions = {}) {
  if (profileOverride) {
    const doc: SettingsResponse = { ...settingsBody(), profile: profileOverride };
    getSettings = () => json(doc);
    onPut = (url, body) => {
      if (url === '/api/settings/profile' && body && typeof body === 'object') {
        doc.profile = { ...doc.profile, ...(body as Partial<SettingsResponse['profile']>) };
      }
      return json(doc);
    };
  }
  return render(
    <AppProviders client={client}>
      <SettingsModal onClose={onClose} />
    </AppProviders>,
  );
}

beforeEach(() => {
  requests.length = 0;
  getSettings = () => json(settingsBody());
  getRecgovStatus = () =>
    json({ configured: false, username: null, session: 'not_configured' });
  onPut = () => json(settingsBody());
  client = createTestQueryClient();
  stubApi();
});

afterEach(() => {
  vi.unstubAllGlobals();
  client.clear();
});

describe('loading', () => {
  test('opens on the profile tab with the saved values', async () => {
    renderSettingsModal();
    expect(await screen.findByLabelText('Display name')).toHaveValue('Ada');
  });

  test('a failed load reports a message instead of an empty form', async () => {
    getSettings = () => json({ error: 'encryption_unavailable' }, 500);
    renderSettingsModal();

    expect(
      await screen.findByText("Secret storage isn't configured on the server."),
    ).toBeInTheDocument();
    expect(screen.queryByLabelText('Display name')).not.toBeInTheDocument();
  });
});

describe('save', () => {
  test('Save is disabled until something changes', async () => {
    renderSettingsModal();
    await screen.findByLabelText('Display name');

    expect(screen.getByRole('button', { name: 'Save' })).toBeDisabled();

    await userEvent.type(screen.getByLabelText('Display name'), 'x');

    expect(screen.getByRole('button', { name: 'Save' })).not.toBeDisabled();
  });

  // Profile and Appearance share one payload, so each Save has to gate on its own
  // slice — otherwise editing here would arm the other section's button.
  test('a display-name edit leaves Appearance clean', async () => {
    renderSettingsModal();
    await screen.findByLabelText('Display name');
    await userEvent.type(screen.getByLabelText('Display name'), 'x');

    await userEvent.click(screen.getByRole('button', { name: 'Appearance' }));

    expect(screen.getByRole('button', { name: 'Save' })).toBeDisabled();
  });

  test('a theme edit leaves Profile clean', async () => {
    renderSettingsModal();
    await userEvent.click(await screen.findByRole('button', { name: 'Appearance' }));
    await userEvent.click(screen.getByRole('radio', { name: 'Dark' }));
    expect(screen.getByRole('button', { name: 'Save' })).not.toBeDisabled();

    await userEvent.click(screen.getByRole('button', { name: 'Profile' }));

    expect(screen.getByRole('button', { name: 'Save' })).toBeDisabled();
  });

  test('saving from Appearance PUTs the whole profile payload', async () => {
    renderSettingsModal();
    await userEvent.click(await screen.findByRole('button', { name: 'Appearance' }));
    await userEvent.click(screen.getByRole('radio', { name: 'Dark' }));
    await userEvent.click(screen.getByRole('button', { name: 'Save' }));

    await waitFor(() => expect(putTo('/api/settings/profile')).toBeTruthy());
    expect(putTo('/api/settings/profile')!.body).toEqual({
      display_name: 'Ada',
      theme: 'dark',
    });
  });

  test('saving the profile PUTs just the display name and confirms', async () => {
    renderSettingsModal();
    await screen.findByLabelText('Display name');

    await userEvent.type(screen.getByLabelText('Display name'), ' Lovelace');
    await userEvent.click(screen.getByRole('button', { name: 'Save' }));

    await waitFor(() => expect(putTo('/api/settings/profile')).toBeTruthy());
    expect(putTo('/api/settings/profile')!.body).toEqual({
      display_name: 'Ada Lovelace',
      theme: 'system',
    });
    expect(await screen.findByText('Settings saved.')).toBeInTheDocument();
  });

  test('a failed save maps the code to a message and leaves Save reachable', async () => {
    onPut = () => json({ error: 'invalid_field' }, 400);
    renderSettingsModal();
    await screen.findByLabelText('Display name');

    await userEvent.type(screen.getByLabelText('Display name'), 'x');
    await userEvent.click(screen.getByRole('button', { name: 'Save' }));

    expect(await screen.findByText('Please check the highlighted fields.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Save' })).not.toBeDisabled();
  });

  test('after saving a token the masked hint reflects the server, not what was typed', async () => {
    renderSettingsModal();
    await screen.findByLabelText('Display name');

    await userEvent.click(screen.getByRole('button', { name: 'Notifications' }));
    await userEvent.type(await screen.findByLabelText('Slack bot token'), 'xoxb-brand-new');

    // The subsequent GET is authoritative over the mutation response.
    const stored = () => json(settingsBody({ slack_configured: true, slack_token_hint: 'nnew' }));
    onPut = stored;
    getSettings = stored;
    await userEvent.click(screen.getByRole('button', { name: 'Save' }));

    expect(await screen.findByText('••••nnew')).toBeInTheDocument();
    // And the typed secret is gone from the DOM entirely.
    expect(screen.queryByLabelText('Slack bot token')).not.toBeInTheDocument();
  });

  test('the notifications save omits an untouched token', async () => {
    renderSettingsModal();
    await screen.findByLabelText('Display name');

    await userEvent.click(screen.getByRole('button', { name: 'Notifications' }));
    await userEvent.type(await screen.findByLabelText('Slack channel'), '-x');
    await userEvent.click(screen.getByRole('button', { name: 'Save' }));

    await waitFor(() => expect(putTo('/api/settings/notifications')).toBeTruthy());
    const body = putTo('/api/settings/notifications')!.body as Record<string, unknown>;
    expect(body).not.toHaveProperty('slack_token');
    expect(body.slack_channel).toBe('#alerts-x');
  });
});

describe('tabs', () => {
  test('the account tab has no Save button', async () => {
    renderSettingsModal();
    await screen.findByLabelText('Display name');

    await userEvent.click(screen.getByRole('button', { name: 'Account' }));

    expect(await screen.findByText('ada@example.test')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Save' })).not.toBeInTheDocument();
  });

  test('switching tabs clears a stale notice', async () => {
    renderSettingsModal();
    await screen.findByLabelText('Display name');
    await userEvent.type(screen.getByLabelText('Display name'), 'x');
    await userEvent.click(screen.getByRole('button', { name: 'Save' }));
    await screen.findByText('Settings saved.');

    await userEvent.click(screen.getByRole('button', { name: 'Notifications' }));

    expect(screen.queryByText('Settings saved.')).not.toBeInTheDocument();
  });

  test('the active tab is marked for assistive tech', async () => {
    renderSettingsModal();
    await screen.findByLabelText('Display name');

    const tabs = screen.getByRole('navigation', { name: 'Settings sections' });
    expect(within(tabs).getByRole('button', { name: 'Profile' })).toHaveAttribute(
      'aria-current',
      'true',
    );
    expect(within(tabs).getByRole('button', { name: 'Account' })).not.toHaveAttribute(
      'aria-current',
    );
  });
});

describe('the Booking tab', () => {
  test('Save gates on the booking slice alone', async () => {
    renderSettingsModal();
    await screen.findByLabelText('Display name');

    await userEvent.click(screen.getByRole('button', { name: 'Booking' }));
    expect(await screen.findByLabelText('Recreation.gov email')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Save' })).toBeDisabled();

    await userEvent.type(screen.getByLabelText('Recreation.gov email'), 'ada@example.test');

    expect(screen.getByRole('button', { name: 'Save' })).not.toBeDisabled();
  });

  test('a booking edit does not light up another tab\u2019s Save', async () => {
    renderSettingsModal();
    await screen.findByLabelText('Display name');
    await userEvent.click(screen.getByRole('button', { name: 'Booking' }));
    await userEvent.type(await screen.findByLabelText('Recreation.gov email'), 'ada@example.test');

    await userEvent.click(screen.getByRole('button', { name: 'Notifications' }));

    expect(screen.getByRole('button', { name: 'Save' })).toBeDisabled();
  });

  test('saving PUTs the credentials and omits an untouched password', async () => {
    getSettings = () => json(settingsBody({}, CONFIGURED_BOOKING));
    renderSettingsModal();
    await screen.findByLabelText('Display name');

    await userEvent.click(screen.getByRole('button', { name: 'Booking' }));
    await userEvent.type(await screen.findByLabelText('Recreation.gov email'), '.uk');
    await userEvent.click(screen.getByRole('button', { name: 'Save' }));

    await waitFor(() => expect(putTo(RECGOV_URL)).toBeTruthy());
    const body = putTo(RECGOV_URL)!.body as Record<string, unknown>;
    expect(body.username).toBe('ada@example.test.uk');
    expect(body).not.toHaveProperty('password');
    expect(await screen.findByText('Settings saved.')).toBeInTheDocument();
  });

  test('a saved password shows as placeholder dots, never as characters', async () => {
    renderSettingsModal();
    await screen.findByLabelText('Display name');

    await userEvent.click(screen.getByRole('button', { name: 'Booking' }));
    await userEvent.type(await screen.findByLabelText('Recreation.gov email'), 'ada@example.test');
    await userEvent.type(screen.getByLabelText('Recreation.gov password'), 'hunter2-secret');

    const stored = () => json(settingsBody({}, CONFIGURED_BOOKING));
    onPut = stored;
    getSettings = stored;
    await userEvent.click(screen.getByRole('button', { name: 'Save' }));

    // The field stays, emptied, with fixed-length dots as its PLACEHOLDER — so
    // nothing real is in the DOM and the length is not the stored one either.
    const password = await screen.findByLabelText('Recreation.gov password');
    await waitFor(() => expect(password).toHaveValue(''));
    expect(password).toHaveAttribute('placeholder', '\u2022'.repeat(10));
  });

  test('the session row never blocks the panel when the companion is down', async () => {
    getSettings = () => json(settingsBody({}, CONFIGURED_BOOKING));
    getRecgovStatus = () =>
      json({
        configured: true,
        username: 'ada@example.test',
       
        session: 'companion_unavailable',
        detail: 'connection refused',
      });
    renderSettingsModal();
    await screen.findByLabelText('Display name');

    await userEvent.click(screen.getByRole('button', { name: 'Booking' }));

    expect(await screen.findByLabelText('Recreation.gov email')).toBeInTheDocument();
    expect(
      await screen.findByText('Booking service unavailable \u2014 status unknown'),
    ).toBeInTheDocument();
  });
});

describe('remove rec.gov credentials', () => {
  test('reports how many active add-to-cart watches it stranded', async () => {
    getSettings = () => json(settingsBody({}, CONFIGURED_BOOKING));
    renderSettingsModal();
    await screen.findByLabelText('Display name');

    // Removal lives on Booking now, beside the credentials it removes.
    await userEvent.click(screen.getByRole('button', { name: 'Booking' }));
    expect(await screen.findByText('Danger zone')).toBeInTheDocument();

    onPut = () => json({ removed: true, stranded_atc_watches: 2, companion_signed_out: false });
    getSettings = () => json(settingsBody());

    await userEvent.click(screen.getByRole('button', { name: 'Remove rec.gov credentials' }));
    await userEvent.click(screen.getByRole('button', { name: 'Confirm removal' }));

    expect(
      await screen.findByText(
        'Recreation.gov credentials removed. 2 active add-to-cart watches will fail until you add them again.',
      ),
    ).toBeInTheDocument();
    await waitFor(() =>
      expect(
        screen.queryByRole('button', { name: 'Remove rec.gov credentials' }),
      ).not.toBeInTheDocument(),
    );
  });
});

describe('disconnect Slack', () => {
  test('clears the token and refreshes, so the danger zone goes away', async () => {
    getSettings = () => json(settingsBody({ slack_configured: true, slack_token_hint: 'ab12' }));
    renderSettingsModal();
    await screen.findByLabelText('Display name');

    await userEvent.click(screen.getByRole('button', { name: 'Account' }));
    expect(await screen.findByText('Danger zone')).toBeInTheDocument();

    // After the DELETE the document no longer has a token.
    onPut = () => new Response(null, { status: 204 });
    getSettings = () => json(settingsBody());

    await userEvent.click(screen.getByRole('button', { name: 'Disconnect Slack' }));
    await userEvent.click(screen.getByRole('button', { name: 'Confirm disconnect' }));

    await waitFor(() =>
      expect(requests.some((r) => r.method === 'DELETE' && r.url.includes('/slack'))).toBe(true),
    );
    await waitFor(() => expect(screen.queryByText('Danger zone')).not.toBeInTheDocument());
  });
});

// The guarantee: whenever the modal is closed, the applied theme equals the
// saved one. The theme store is a module singleton never reset between tests,
// so `afterEach` restores `choice` itself.
describe('theme preview on close', () => {
  afterEach(() => {
    useThemeStore.getState().setChoice('system');
  });

  // The only test here that needs no interaction, so the only one that pins the
  // "server is authoritative on load" half — the others would pass even if
  // `useSettings` never pushed the loaded theme into the store.
  test('applies the loaded theme with no interaction, even overriding what was already applied', async () => {
    useThemeStore.getState().setChoice('light');

    renderSettingsModal({ profile: { ...profile, theme: 'dark' } });
    await screen.findByLabelText('Display name');

    expect(useThemeStore.getState().choice).toBe('dark');
    expect(document.documentElement.classList.contains('mode-dark')).toBe(true);
  });

  test('reverts an unsaved theme preview when the modal closes', async () => {
    const { unmount } = renderSettingsModal({ profile: { ...profile, theme: 'light' } });

    await userEvent.click(await screen.findByRole('button', { name: 'Appearance' }));
    await userEvent.click(screen.getByRole('radio', { name: 'Dark' }));
    expect(document.documentElement.classList.contains('mode-dark')).toBe(true);

    unmount();

    expect(useThemeStore.getState().choice).toBe('light');
    expect(document.documentElement.classList.contains('mode-dark')).toBe(false);
  });

  test('keeps a saved theme when the modal closes', async () => {
    const { unmount } = renderSettingsModal({ profile: { ...profile, theme: 'light' } });

    await userEvent.click(await screen.findByRole('button', { name: 'Appearance' }));
    await userEvent.click(screen.getByRole('radio', { name: 'Dark' }));
    await userEvent.click(screen.getByRole('button', { name: 'Save' }));
    await screen.findByText('Settings saved.');

    unmount();

    expect(useThemeStore.getState().choice).toBe('dark');
    expect(document.documentElement.classList.contains('mode-dark')).toBe(true);
  });
});
