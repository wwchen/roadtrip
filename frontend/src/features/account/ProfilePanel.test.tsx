import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest';
import type { Profile } from '@/api/account-api';
import { useThemeStore } from '@/stores/themeStore';
import {
  ProfilePanel,
  buildProfilePayload,
  isProfileDirty,
  profileValuesOf,
  type ProfileValues,
} from './ProfilePanel';

const profile: Profile = {
  display_name: 'William Chen',
  login_email: 'wm@example.com',
  is_email_verified: true,
  roles: [],
  provider_label: null,
  theme: 'system',
};

const settings = { profile, notifications: {} } as never;

beforeEach(() => {
  document.documentElement.className = 'theme-roadtrip-zion';
  vi.stubGlobal('matchMedia', () => ({
    matches: false,
    addEventListener: () => {},
    removeEventListener: () => {},
  }));
});

// The theme store is a module singleton, never reset between tests, and the
// "picking Dark" test drives it for real — so it restores `choice` itself.
afterEach(() => {
  useThemeStore.getState().setChoice('system');
  vi.unstubAllGlobals();
});

describe('profile values', () => {
  test('seeds the theme from the profile', () => {
    expect(profileValuesOf(settings).theme).toBe('system');
  });

  test('coerces an unknown theme to system', () => {
    const odd = { profile: { ...profile, theme: 'sepia' }, notifications: {} } as never;
    expect(profileValuesOf(odd).theme).toBe('system');
  });

  test('a changed theme is dirty', () => {
    const values: ProfileValues = { display_name: 'William Chen', theme: 'dark' };
    expect(isProfileDirty(settings, values)).toBe(true);
  });

  test('an unchanged theme is not dirty', () => {
    const values: ProfileValues = { display_name: 'William Chen', theme: 'system' };
    expect(isProfileDirty(settings, values)).toBe(false);
  });

  test('the payload carries the theme', () => {
    expect(buildProfilePayload({ display_name: 'Wm', theme: 'dark' })).toEqual({
      display_name: 'Wm',
      theme: 'dark',
    });
  });
});

describe('the Appearance control', () => {
  test('renders the three options', () => {
    render(<ProfilePanel profile={profile} values={profileValuesOf(settings)} onChange={() => {}} />);
    expect(screen.getByRole('radio', { name: 'Light' })).toBeInTheDocument();
    expect(screen.getByRole('radio', { name: 'Dark' })).toBeInTheDocument();
    expect(screen.getByRole('radio', { name: 'System' })).toBeInTheDocument();
  });

  test('picking Dark previews immediately and reports the change', async () => {
    const onChange = vi.fn();
    render(<ProfilePanel profile={profile} values={profileValuesOf(settings)} onChange={onChange} />);

    await userEvent.click(screen.getByRole('radio', { name: 'Dark' }));

    expect(onChange).toHaveBeenCalledWith({ display_name: 'William Chen', theme: 'dark' });
    expect(useThemeStore.getState().choice).toBe('dark');
    expect(document.documentElement.classList.contains('mode-dark')).toBe(true);
  });

  // aria-label names the group; aria-describedby is what pulls in the help text,
  // so it has to point at a real element.
  test('describes the radiogroup with the help text, for assistive tech', () => {
    render(<ProfilePanel profile={profile} values={profileValuesOf(settings)} onChange={() => {}} />);

    const radiogroup = screen.getByRole('radiogroup');
    const describedBy = radiogroup.getAttribute('aria-describedby');

    expect(describedBy).toBeTruthy();
    expect(document.getElementById(describedBy!)).toHaveTextContent(
      'System follows your device setting.',
    );
  });
});
