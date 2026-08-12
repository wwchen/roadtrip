import { afterEach, describe, expect, test, vi } from 'vitest';
import { act, render, screen } from '@testing-library/react';
import { Drawer } from './Drawer';

/** The panel height every percentage threshold below is measured against. */
const SHEET_HEIGHT = 500;
/** 30% of the sheet dismisses, so this clears it and 100px does not. */
const PAST_DISMISS = 200;

const panel = () => screen.getByRole('dialog');
const handle = () => panel().querySelector('.rt-drawer-handle') as HTMLElement;

/**
 * A TouchEvent stand-in.
 *
 * jsdom implements neither `TouchEvent` nor `Touch`, so the listeners get a plain
 * event carrying the two touch lists they read. `cancelable` matters: the hook calls
 * `preventDefault` only on a cancelable event, and asserting that it claims the
 * gesture is part of the point.
 */
function touch(type: string, y: number, x = 0): Event {
  const event = new Event(type, { bubbles: true, cancelable: true });
  const list = [{ clientX: x, clientY: y }];
  Object.assign(event, {
    touches: type === 'touchend' ? [] : list,
    changedTouches: list,
  });
  return event;
}

/** jsdom reports every box as 0×0; the hook measures the sheet on touchstart. */
function stubHeight(element: HTMLElement, height: number): void {
  vi.spyOn(element, 'getBoundingClientRect').mockReturnValue({
    height,
    width: 400,
    top: 0,
    bottom: height,
    left: 0,
    right: 400,
    x: 0,
    y: 0,
    toJSON: () => ({}),
  });
}

interface DragOptions {
  /** Start on the grab bar rather than in the body. */
  fromHandle?: boolean;
  /** Horizontal travel, for the "this is a sideways pan" branch. */
  dx?: number;
  /** Where in the panel a body drag starts. */
  target?: HTMLElement;
}

/** One complete gesture: down, a few moves, up. Returns the last move event. */
function drag(dy: number, { fromHandle = true, dx = 0, target }: DragOptions = {}): Event {
  const root = panel();
  const from = fromHandle ? handle() : (target ?? (root.querySelector('.rt-drawer-content') as HTMLElement));
  stubHeight(root, SHEET_HEIGHT);

  let last = touch('touchmove', 0);
  act(() => {
    from.dispatchEvent(touch('touchstart', 0));
    for (const step of [0.5, 1]) {
      last = touch('touchmove', dy * step, dx * step);
      root.dispatchEvent(last);
    }
    root.dispatchEvent(touch('touchend', dy, dx));
  });
  return last;
}

const open = (onClose = vi.fn()) => {
  render(
    <Drawer open onClose={onClose}>
      <button type="button">Book</button>
      <p>body copy</p>
    </Drawer>,
  );
  return onClose;
};

afterEach(() => vi.restoreAllMocks());

describe('the drag gesture', () => {
  test('is bound to the panel once it mounts', () => {
    const onClose = open();

    drag(PAST_DISMISS);

    expect(onClose).toHaveBeenCalledTimes(1);
  });

  test('is bound when the drawer opens later, not only when it mounts open', () => {
    const onClose = vi.fn();
    const { rerender } = render(
      <Drawer open={false} onClose={onClose}>
        <p>body copy</p>
      </Drawer>,
    );
    expect(screen.queryByRole('dialog')).toBeNull();

    act(() => {
      rerender(
        <Drawer open onClose={onClose}>
          <p>body copy</p>
        </Drawer>,
      );
    });
    drag(PAST_DISMISS);

    expect(onClose).toHaveBeenCalledTimes(1);
  });

  test('a short drag springs back instead of dismissing', () => {
    const onClose = open();

    // 100 of 500 is 20%, under the 30% threshold.
    drag(100);

    expect(onClose).not.toHaveBeenCalled();
    expect(panel().style.height).toBe('');
  });

  test('follows the finger while dragging, then hands the height back to CSS', () => {
    open();
    const root = panel();
    stubHeight(root, SHEET_HEIGHT);

    act(() => {
      handle().dispatchEvent(touch('touchstart', 0));
      root.dispatchEvent(touch('touchmove', 120));
    });
    expect(root.style.height).toBe(`${SHEET_HEIGHT - 120}px`);

    act(() => {
      root.dispatchEvent(touch('touchend', 120));
    });
    // Cleared even on a spring-back, or the sheet would be stuck at the drag height
    // and stop responding to the `--full` class.
    expect(root.style.height).toBe('');
  });

  test('claims the gesture it is handling', () => {
    open();

    const move = drag(PAST_DISMISS);

    expect(move.defaultPrevented).toBe(true);
  });

  test('an upward drag on the handle snaps to full height', () => {
    const onClose = open();

    drag(-120);

    expect(onClose).not.toHaveBeenCalled();
    expect(panel().className).toContain('rt-drawer--full');
  });

  test('a small upward nudge does not snap', () => {
    open();

    drag(-40);

    expect(panel().className).not.toContain('rt-drawer--full');
  });
});

describe('telling a drag from a scroll', () => {
  test('a body drag past the slop dismisses', () => {
    const onClose = open();

    drag(PAST_DISMISS, { fromHandle: false });

    expect(onClose).toHaveBeenCalledTimes(1);
  });

  test('a tap in the body does nothing', () => {
    const onClose = open();

    drag(4, { fromHandle: false });

    expect(onClose).not.toHaveBeenCalled();
    expect(panel().style.height).toBe('');
  });

  test('a downward drag on scrolled content scrolls instead of dismissing', () => {
    const onClose = open();
    Object.defineProperty(panel(), 'scrollTop', { value: 120, configurable: true });

    drag(PAST_DISMISS, { fromHandle: false });

    expect(onClose).not.toHaveBeenCalled();
  });

  test('a sideways pan is released to whatever scrolls horizontally', () => {
    const onClose = open();

    const move = drag(PAST_DISMISS, { fromHandle: false, dx: 300 });

    expect(onClose).not.toHaveBeenCalled();
    expect(move.defaultPrevented).toBe(false);
  });

  test('a drag that began on a control belongs to the control', () => {
    const onClose = open();

    drag(PAST_DISMISS, {
      fromHandle: false,
      target: screen.getByRole('button', { name: 'Book' }),
    });

    expect(onClose).not.toHaveBeenCalled();
  });

  test('a body drag that flips upward is handed back', () => {
    const onClose = open();
    const root = panel();
    stubHeight(root, SHEET_HEIGHT);
    const content = root.querySelector('.rt-drawer-content') as HTMLElement;

    act(() => {
      content.dispatchEvent(touch('touchstart', 200));
      root.dispatchEvent(touch('touchmove', 240)); // past slop, downward: claimed
      root.dispatchEvent(touch('touchmove', 150)); // flipped upward: released
      root.dispatchEvent(touch('touchend', 150));
    });

    expect(onClose).not.toHaveBeenCalled();
    expect(root.style.height).toBe('');
  });

  test('a two-finger touch is ignored', () => {
    const onClose = open();
    const root = panel();
    stubHeight(root, SHEET_HEIGHT);

    act(() => {
      const start = new Event('touchstart', { bubbles: true, cancelable: true });
      Object.assign(start, {
        touches: [{ clientX: 0, clientY: 0 }, { clientX: 40, clientY: 0 }],
        changedTouches: [{ clientX: 0, clientY: 0 }],
      });
      handle().dispatchEvent(start);
      root.dispatchEvent(touch('touchmove', PAST_DISMISS));
      root.dispatchEvent(touch('touchend', PAST_DISMISS));
    });

    expect(onClose).not.toHaveBeenCalled();
  });

  test('a cancelled touch dismisses nothing and leaves no inline height', () => {
    const onClose = open();
    const root = panel();
    stubHeight(root, SHEET_HEIGHT);

    act(() => {
      handle().dispatchEvent(touch('touchstart', 0));
      root.dispatchEvent(touch('touchmove', 120));
      root.dispatchEvent(touch('touchcancel', 120));
    });

    expect(onClose).not.toHaveBeenCalled();
    expect(root.style.height).toBe('');
  });
});
