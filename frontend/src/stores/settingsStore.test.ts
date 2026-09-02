import { beforeEach, describe, expect, test } from 'vitest';
import { useSettingsStore } from './settingsStore';

describe('settingsStore', () => {
  beforeEach(() => useSettingsStore.getState().closeSettings());

  test('starts closed, on no particular section', () => {
    expect(useSettingsStore.getState().open).toBe(false);
    expect(useSettingsStore.getState().tab).toBeNull();
  });

  test('opens on a named section', () => {
    useSettingsStore.getState().openSettings('booking');

    expect(useSettingsStore.getState().open).toBe(true);
    expect(useSettingsStore.getState().tab).toBe('booking');
  });

  test('opens with no section when none is named', () => {
    useSettingsStore.getState().openSettings();

    expect(useSettingsStore.getState().open).toBe(true);
    expect(useSettingsStore.getState().tab).toBeNull();
  });

  test('forgets the section on close, so the next open is not haunted by it', () => {
    useSettingsStore.getState().openSettings('booking');
    useSettingsStore.getState().closeSettings();

    expect(useSettingsStore.getState().open).toBe(false);
    expect(useSettingsStore.getState().tab).toBeNull();
  });
});
