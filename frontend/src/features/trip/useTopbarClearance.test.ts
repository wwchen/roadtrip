import { renderHook, act } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { useTopbarClearance } from './useTopbarClearance';

const CLEARANCE_VAR = '--rt-topbar-bottom';

const published = (): string =>
  document.documentElement.style.getPropertyValue(CLEARANCE_VAR);

/**
 * A ResizeObserver whose callback the test can fire. The global stub in
 * vitest.setup.ts never calls back, so without this the republication path — the
 * whole reason the hook observes anything — would go unexercised.
 */
let fireResize: () => void;
const realResizeObserver = globalThis.ResizeObserver;

beforeEach(() => {
  globalThis.ResizeObserver = class {
    constructor(callback: () => void) {
      fireResize = callback;
    }
    observe(): void {}
    unobserve(): void {}
    disconnect(): void {}
  } as unknown as typeof ResizeObserver;
});

afterEach(() => {
  globalThis.ResizeObserver = realResizeObserver;
  document.documentElement.style.removeProperty(CLEARANCE_VAR);
});

function stub(element: HTMLElement, box: { top: number; height: number }) {
  Object.defineProperty(element, 'offsetTop', { value: box.top, configurable: true });
  Object.defineProperty(element, 'offsetHeight', { value: box.height, configurable: true });
}

function mountPanel(box: { top: number; height: number }, popoverHeight?: number) {
  const panel = document.createElement('div');
  stub(panel, box);

  if (popoverHeight !== undefined) {
    const popover = document.createElement('div');
    popover.className = 'tb-dropdown';
    Object.defineProperty(popover, 'offsetHeight', { value: popoverHeight });
    panel.append(popover);
  }

  document.body.append(panel);
  return { panel, ...renderHook(() => useTopbarClearance({ current: panel })) };
}

describe('useTopbarClearance', () => {
  it("publishes the panel's bottom edge, not its height", () => {
    // A consumer given only the height would sit 10px too high, and the panel's
    // own inset grows to the safe-area on a notched device.
    mountPanel({ top: 10, height: 80 });
    expect(published()).toBe('90px');
  });

  it('excludes the search popover, which overlays rather than reserving room', () => {
    mountPanel({ top: 10, height: 300 }, 220);
    expect(published()).toBe('90px');
  });

  it('republishes when the panel grows', () => {
    const { panel } = mountPanel({ top: 10, height: 80 });
    expect(published()).toBe('90px');

    stub(panel, { top: 10, height: 400 });
    act(() => fireResize());

    expect(published()).toBe('410px');
  });

  it('stops reserving room once the panel unmounts', () => {
    const { unmount } = mountPanel({ top: 10, height: 80 });
    unmount();
    expect(published()).toBe('');
  });
});
