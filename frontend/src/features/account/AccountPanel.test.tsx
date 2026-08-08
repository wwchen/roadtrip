// The two account panels. The legacy modules had no tests, so this is the first
// pinning of their behaviour — including the one that matters operationally:
// "Disconnect Slack" only exists when a token is actually stored.
import { describe, expect, test, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { SettingsResponse } from '@/api/account-api';
import { AccountPanel } from './AccountPanel';
import {
  ProfilePanel,
  buildProfilePayload,
  isProfileDirty,
  profileValuesOf,
} from './ProfilePanel';

const settings = (over: {
  profile?: Partial<SettingsResponse['profile']>;
  notifications?: Partial<SettingsResponse['notifications']>;
} = {}): SettingsResponse => ({
  profile: {
    display_name: 'Ada',
    login_email: 'ada@example.test',
    is_email_verified: true,
    roles: [],
    provider_label: 'Clerk',
    ...over.profile,
  },
  notifications: {
    notification_email: null,
    slack_channel: null,
    slack_configured: false,
    slack_token_hint: null,
    ...over.notifications,
  },
});

describe('AccountPanel', () => {
  test('shows who is signed in', () => {
    render(
      <AccountPanel settings={settings()} onSignOut={vi.fn()} onDisconnectSlack={vi.fn()} />,
    );
    expect(screen.getByText('ada@example.test')).toBeInTheDocument();
  });

  test('signing out takes two clicks', async () => {
    const onSignOut = vi.fn();
    render(
      <AccountPanel settings={settings()} onSignOut={onSignOut} onDisconnectSlack={vi.fn()} />,
    );

    await userEvent.click(screen.getByRole('button', { name: 'Sign out' }));
    expect(onSignOut).not.toHaveBeenCalled();

    await userEvent.click(screen.getByRole('button', { name: 'Confirm sign out' }));
    expect(onSignOut).toHaveBeenCalledTimes(1);
  });

  // Offering to disconnect nothing reads as a broken button.
  test('the danger zone is absent when no Slack token is stored', () => {
    render(
      <AccountPanel settings={settings()} onSignOut={vi.fn()} onDisconnectSlack={vi.fn()} />,
    );
    expect(screen.queryByText('Danger zone')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Disconnect Slack' })).not.toBeInTheDocument();
  });

  test('the danger zone appears once one is', async () => {
    const onDisconnectSlack = vi.fn();
    render(
      <AccountPanel
        settings={settings({ notifications: { slack_configured: true } })}
        onSignOut={vi.fn()}
        onDisconnectSlack={onDisconnectSlack}
      />,
    );

    expect(screen.getByText('Danger zone')).toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: 'Disconnect Slack' }));
    await userEvent.click(screen.getByRole('button', { name: 'Confirm disconnect' }));

    expect(onDisconnectSlack).toHaveBeenCalledTimes(1);
  });
});

describe('ProfilePanel', () => {
  test('seeds the display name and shows the login email', () => {
    const s = settings();
    render(<ProfilePanel profile={s.profile} values={profileValuesOf(s)} onChange={vi.fn()} />);

    expect(screen.getByLabelText('Display name')).toHaveValue('Ada');
    expect(screen.getByText('ada@example.test')).toBeInTheDocument();
  });

  test('shows the verified badge only for a verified address', () => {
    const verified = settings();
    const { unmount } = render(
      <ProfilePanel
        profile={verified.profile}
        values={profileValuesOf(verified)}
        onChange={vi.fn()}
      />,
    );
    expect(screen.getByLabelText('Verified')).toBeInTheDocument();
    unmount();

    const unverified = settings({ profile: { is_email_verified: false } });
    render(
      <ProfilePanel
        profile={unverified.profile}
        values={profileValuesOf(unverified)}
        onChange={vi.fn()}
      />,
    );
    expect(screen.queryByLabelText('Verified')).not.toBeInTheDocument();
  });

  test('reports edits to its parent', async () => {
    const onChange = vi.fn();
    const s = settings({ profile: { display_name: '' } });
    render(<ProfilePanel profile={s.profile} values={profileValuesOf(s)} onChange={onChange} />);

    await userEvent.type(screen.getByLabelText('Display name'), 'Grace');

    expect(onChange).toHaveBeenLastCalledWith({ display_name: 'Grace' });
  });
});

describe('profile dirty tracking', () => {
  test('a null saved name is equivalent to empty, not to the string "null"', () => {
    const s = settings({ profile: { display_name: null } });
    expect(profileValuesOf(s)).toEqual({ display_name: '' });
    expect(isProfileDirty(s, { display_name: '' })).toBe(false);
  });

  test('an unchanged name is not dirty', () => {
    expect(isProfileDirty(settings(), { display_name: 'Ada' })).toBe(false);
  });

  test('a changed name is dirty', () => {
    expect(isProfileDirty(settings(), { display_name: 'Grace' })).toBe(true);
  });

  // Clearing a name is an edit, so Save has to stay reachable.
  test('clearing a set name is dirty', () => {
    expect(isProfileDirty(settings(), { display_name: '' })).toBe(true);
  });

  test('the payload carries just the display name', () => {
    expect(buildProfilePayload({ display_name: 'Grace' })).toEqual({ display_name: 'Grace' });
  });
});
