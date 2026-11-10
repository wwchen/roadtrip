import { describe, expect, test, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import type { Profile } from '@/api/account-api';
import {
  buildProfilePayload,
  isProfileDirty,
  ProfilePanel,
  profileValuesOf,
  type ProfileValues,
} from './ProfilePanel';

const profile: Profile = {
  display_name: 'William Chen',
  login_email: 'wm@example.com',
  is_email_verified: true,
  roles: [],
  provider_label: undefined,
  theme: 'system',
};

const settings = { profile, notifications: {} } as never;

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

describe('the profile panel', () => {
  test('shows a display name the modal reloads, without a remount', () => {
    const values: ProfileValues = { display_name: 'William Chen', theme: 'system' };
    const { rerender } = render(<ProfilePanel profile={profile} values={values} onChange={vi.fn()} />);

    rerender(<ProfilePanel profile={profile} values={{ ...values, display_name: 'Wm' }} onChange={vi.fn()} />);

    expect(screen.getByLabelText('Display name')).toHaveValue('Wm');
  });
});
