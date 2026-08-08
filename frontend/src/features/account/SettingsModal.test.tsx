// The settings modal: three tabs over one document, per-tab save, and the reseed
// that keeps the masked Slack hint honest after a save.
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest';
import { QueryClient } from '@tanstack/react-query';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { AppProviders } from '@/app/AppProviders';
import type { SettingsResponse } from '@/api/account-api';
import { SettingsModal } from './SettingsModal';

const settingsBody = (over: Partial<SettingsResponse['notifications']> = {}): SettingsResponse => ({
  profile: {
    display_name: 'Ada',
    login_email: 'ada@example.test',
    is_email_verified: true,
    roles: [],
    provider_label: 'Clerk',
  },
  notifications: {
    notification_email: null,
    slack_channel: '#alerts',
    slack_configured: false,
    slack_token_hint: null,
    ...over,
  },
});

interface Recorded {
  url: string;
  method: string;
  body: unknown;
}

const requests: Recorded[] = [];
let getSettings: () => Response;
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
      if (method === 'PUT' || method === 'POST' || method === 'DELETE') return onPut(url, body);
      throw new Error(`unstubbed ${method} ${url}`);
    }),
  );
}

const putTo = (url: string): Recorded | undefined =>
  requests.find((r) => r.url === url && r.method === 'PUT');

let client: QueryClient;

function renderModal(onClose = vi.fn()) {
  return render(
    <AppProviders client={client}>
      <SettingsModal onClose={onClose} />
    </AppProviders>,
  );
}

beforeEach(() => {
  requests.length = 0;
  getSettings = () => json(settingsBody());
  onPut = () => json(settingsBody());
  client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  stubApi();
});

afterEach(() => {
  vi.unstubAllGlobals();
  client.clear();
});

describe('loading', () => {
  test('opens on the profile tab with the saved values', async () => {
    renderModal();
    expect(await screen.findByLabelText('Display name')).toHaveValue('Ada');
  });

  test('a failed load reports a message instead of an empty form', async () => {
    getSettings = () => json({ error: 'encryption_unavailable' }, 500);
    renderModal();

    expect(
      await screen.findByText("Secret storage isn't configured on the server."),
    ).toBeInTheDocument();
    expect(screen.queryByLabelText('Display name')).not.toBeInTheDocument();
  });
});

describe('save', () => {
  test('Save is disabled until something changes', async () => {
    renderModal();
    await screen.findByLabelText('Display name');

    expect(screen.getByRole('button', { name: 'Save' })).toBeDisabled();

    await userEvent.type(screen.getByLabelText('Display name'), 'x');

    expect(screen.getByRole('button', { name: 'Save' })).not.toBeDisabled();
  });

  test('saving the profile PUTs just the display name and confirms', async () => {
    renderModal();
    await screen.findByLabelText('Display name');

    await userEvent.type(screen.getByLabelText('Display name'), ' Lovelace');
    await userEvent.click(screen.getByRole('button', { name: 'Save' }));

    await waitFor(() => expect(putTo('/api/settings/profile')).toBeTruthy());
    expect(putTo('/api/settings/profile')!.body).toEqual({ display_name: 'Ada Lovelace' });
    expect(await screen.findByText('Settings saved.')).toBeInTheDocument();
  });

  test('a failed save maps the code to a message and leaves Save reachable', async () => {
    onPut = () => json({ error: 'invalid_field' }, 400);
    renderModal();
    await screen.findByLabelText('Display name');

    await userEvent.type(screen.getByLabelText('Display name'), 'x');
    await userEvent.click(screen.getByRole('button', { name: 'Save' }));

    expect(await screen.findByText('Please check the highlighted fields.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Save' })).not.toBeDisabled();
  });

  // The whole reason the legacy modal re-read settings after saving: storing a token
  // produces a new hint, and the masked field has to show the new one.
  test('after saving a token the masked hint reflects the server, not what was typed', async () => {
    renderModal();
    await screen.findByLabelText('Display name');

    await userEvent.click(screen.getByRole('button', { name: 'Notifications' }));
    await userEvent.type(await screen.findByLabelText('Slack bot token'), 'xoxb-brand-new');

    // The save stores the token, so both the PUT response and the subsequent GET
    // carry the new hint. The GET is authoritative — as in the original, which
    // re-read settings after saving and only fell back to the mutation response
    // when that re-read failed.
    const stored = () => json(settingsBody({ slack_configured: true, slack_token_hint: 'nnew' }));
    onPut = stored;
    getSettings = stored;
    await userEvent.click(screen.getByRole('button', { name: 'Save' }));

    expect(await screen.findByText('••••nnew')).toBeInTheDocument();
    // And the typed secret is gone from the DOM entirely.
    expect(screen.queryByLabelText('Slack bot token')).not.toBeInTheDocument();
  });

  test('the notifications save omits an untouched token', async () => {
    renderModal();
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
    renderModal();
    await screen.findByLabelText('Display name');

    await userEvent.click(screen.getByRole('button', { name: 'Account' }));

    expect(await screen.findByText('ada@example.test')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Save' })).not.toBeInTheDocument();
  });

  test('switching tabs clears a stale notice', async () => {
    renderModal();
    await screen.findByLabelText('Display name');
    await userEvent.type(screen.getByLabelText('Display name'), 'x');
    await userEvent.click(screen.getByRole('button', { name: 'Save' }));
    await screen.findByText('Settings saved.');

    await userEvent.click(screen.getByRole('button', { name: 'Notifications' }));

    expect(screen.queryByText('Settings saved.')).not.toBeInTheDocument();
  });

  test('the active tab is marked for assistive tech', async () => {
    renderModal();
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

describe('disconnect Slack', () => {
  test('clears the token and refreshes, so the danger zone goes away', async () => {
    getSettings = () => json(settingsBody({ slack_configured: true, slack_token_hint: 'ab12' }));
    renderModal();
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
