import { render, screen } from '@testing-library/react';
import { afterEach, describe, expect, test, vi } from 'vitest';
import { SettingsHost } from './SettingsHost';
import { useSettingsStore } from '@/stores/settingsStore';

// The real modal is a fetching, portalled LDS surface; this suite is about which
// section the host asks for, so it stands in for one.
vi.mock('@/features/account/SettingsModal', () => ({
  SettingsModal: ({ initialTab }: { initialTab?: string }) => (
    <div data-testid="settings-modal">{initialTab ?? 'default'}</div>
  ),
}));

afterEach(() => useSettingsStore.getState().closeSettings());

describe('the settings host', () => {
  test('renders nothing while the store is closed', () => {
    render(<SettingsHost />);

    expect(screen.queryByTestId('settings-modal')).toBeNull();
  });

  test('opens the modal on the section the store names', () => {
    useSettingsStore.getState().openSettings('booking');
    render(<SettingsHost />);

    expect(screen.getByTestId('settings-modal')).toHaveTextContent('booking');
  });

  test('leaves the section to the modal when the store names none', () => {
    useSettingsStore.getState().openSettings();
    render(<SettingsHost />);

    expect(screen.getByTestId('settings-modal')).toHaveTextContent('default');
  });
});
