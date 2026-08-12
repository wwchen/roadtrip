import { renderHook } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { useTopbarClearance } from './useTopbarClearance';

const CLEARANCE_VAR = '--rt-topbar-h';

const published = (): string =>
  document.documentElement.style.getPropertyValue(CLEARANCE_VAR);

function mountPanel(panelHeight: number, popoverHeight?: number) {
  const panel = document.createElement('div');
  Object.defineProperty(panel, 'offsetHeight', { value: panelHeight });

  if (popoverHeight !== undefined) {
    const popover = document.createElement('div');
    popover.className = 'tb-dropdown';
    Object.defineProperty(popover, 'offsetHeight', { value: popoverHeight });
    panel.append(popover);
  }

  document.body.append(panel);
  return renderHook(() => useTopbarClearance({ current: panel }));
}

describe('useTopbarClearance', () => {
  it('publishes the panel height for the drawer to clear', () => {
    mountPanel(80);
    expect(published()).toBe('80px');
  });

  it('excludes the search popover, which overlays rather than reserving room', () => {
    mountPanel(300, 220);
    expect(published()).toBe('80px');
  });

  it('stops reserving room once the panel unmounts', () => {
    const { unmount } = mountPanel(80);
    unmount();
    expect(published()).toBe('');
  });
});
