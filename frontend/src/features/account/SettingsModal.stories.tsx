import { useEffect, useState } from 'react';
import type { Meta, StoryObj } from '@storybook/react-vite';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { DARK_MODE_CLASS, type ThemeChoice } from '@/lib/theme';
import type { SettingsResponse } from '@/api/account-api';
import { SettingsModal } from './SettingsModal';

/** The class the production shells put on `<html>`. The modal portals to
 *  `document.body`, so it escapes any decorator wrapper — the theme has to be on
 *  the root element, exactly where `applyMode` puts it in production. */
const ZION_THEME_CLASS = 'theme-roadtrip-zion';

const SETTINGS: SettingsResponse = {
  profile: {
    display_name: 'Ada Lovelace',
    login_email: 'ada@example.test',
    is_email_verified: true,
    roles: [],
    provider_label: 'Clerk',
    theme: 'system',
  },
  notifications: {
    notification_email: 'ada@example.test',
    slack_channel: '#trip-alerts',
    slack_configured: true,
    slack_token_hint: 'xoxb-…4f2a',
  },
  booking: {
    recgov_configured: true,
    recgov_username: 'ada@example.test',
  },
};

/** Serves the settings document and accepts every write, so the catalog entry
 *  is interactive — switching sections and picking a theme both work. */
function useStubbedApi(theme: ThemeChoice) {
  const [ready, setReady] = useState(false);
  useEffect(() => {
    const doc: SettingsResponse = { ...SETTINGS, profile: { ...SETTINGS.profile, theme } };
    const original = window.fetch;
    window.fetch = (async (input: unknown) => {
      const url = String(input);
      const body = JSON.stringify(url === '/api/settings' ? doc : { ok: true });
      return new Response(body, { status: 200, headers: { 'Content-Type': 'application/json' } });
    }) as typeof window.fetch;
    setReady(true);
    return () => {
      window.fetch = original;
    };
  }, [theme]);
  return ready;
}

/**
 * Mounts the modal against the stubbed API under the real theme classes.
 *
 * A fresh `QueryClient` per story keeps one entry's edits out of the next, and
 * retries are off so the stub's first answer is the one rendered.
 */
function Demo({ dark, theme = 'system' }: { dark?: boolean; theme?: ThemeChoice }) {
  const ready = useStubbedApi(theme);

  useEffect(() => {
    const root = document.documentElement;
    root.classList.add(ZION_THEME_CLASS);
    root.classList.toggle(DARK_MODE_CLASS, Boolean(dark));
    return () => root.classList.remove(DARK_MODE_CLASS);
  }, [dark]);

  const [client] = useState(
    () => new QueryClient({ defaultOptions: { queries: { retry: false } } }),
  );

  if (!ready) return null;
  return (
    <QueryClientProvider client={client}>
      <SettingsModal onClose={() => {}} />
    </QueryClientProvider>
  );
}

const meta = {
  title: 'Account/SettingsModal',
  parameters: {
    layout: 'fullscreen',
    docs: {
      description: {
        component:
          'The account settings modal: four sections over a left rail — Profile, ' +
          'Appearance, Notifications, Account — beside a panel column, at the ' +
          '`xl` modal width. Profile and Appearance edit two slices of one ' +
          'profile document and each save it; Account only fires actions and so ' +
          'has no Save.',
      },
    },
  },
} satisfies Meta;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = { render: () => <Demo /> };

/** The section the rail exists to make room for. */
export const Appearance: Story = { render: () => <Demo theme="light" /> };

/** The night palette, which is where the rail's selected-row wash and its muted
 *  idle rows have to be checked against each other. */
export const InDarkMode: Story = { render: () => <Demo dark theme="dark" /> };

/** Below 560px the rail can no longer earn its own column and collapses to a
 *  wrapped row above the panel. */
export const Narrow: Story = {
  parameters: { viewport: { defaultViewport: 'mobile1' } },
  render: () => <Demo />,
};
