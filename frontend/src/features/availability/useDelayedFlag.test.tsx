// The skeleton delay.
//
// Small, and worth pinning: the whole reason it exists is that a cache hit must not
// flash a skeleton table on its way to real data, and the failure mode — a trailing
// timer that raises the flag *after* the fetch finished — is invisible in a screenshot.
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest';
import { act, render } from '@testing-library/react';
import { useDelayedFlag } from './useDelayedFlag';

const DELAY = 150;

let raised: boolean;

function Harness({ active }: { active: boolean }) {
  raised = useDelayedFlag(active, DELAY);
  return null;
}

beforeEach(() => {
  vi.useFakeTimers();
});
afterEach(() => {
  vi.useRealTimers();
});

const advance = (ms: number) =>
  act(() => {
    vi.advanceTimersByTime(ms);
  });

describe('the delayed flag', () => {
  test('stays down before the delay', async () => {
    render(<Harness active />);
    expect(raised).toBe(false);

    await advance(DELAY - 1);
    expect(raised).toBe(false);
  });

  test('rises once the delay elapses', async () => {
    render(<Harness active />);

    await advance(DELAY);

    expect(raised).toBe(true);
  });

  // The point of the hook: work that finishes inside the delay shows nothing.
  test('never rises for work that finishes first', async () => {
    const { rerender } = render(<Harness active />);

    await advance(DELAY - 1);
    await act(async () => {
      rerender(<Harness active={false} />);
    });
    await advance(DELAY * 2);

    expect(raised).toBe(false);
  });

  test('drops immediately when the work ends', async () => {
    const { rerender } = render(<Harness active />);
    await advance(DELAY);
    expect(raised).toBe(true);

    await act(async () => {
      rerender(<Harness active={false} />);
    });

    expect(raised).toBe(false);
  });

  // A second slow fetch must get its own grace period, not inherit the first's.
  test('restarts the delay for the next run', async () => {
    const { rerender } = render(<Harness active />);
    await advance(DELAY);
    await act(async () => {
      rerender(<Harness active={false} />);
    });

    await act(async () => {
      rerender(<Harness active />);
    });
    expect(raised).toBe(false);

    await advance(DELAY);
    expect(raised).toBe(true);
  });

  test('starts down for work that never begins', () => {
    render(<Harness active={false} />);

    expect(raised).toBe(false);
  });
});
