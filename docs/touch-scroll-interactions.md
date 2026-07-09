# Touch and scroll interactions

Mobile tap handling inside a scrollable table is not the same as desktop
click handling. A tap can trigger focus changes, scroll anchoring, delayed
layout correction, and click synthesis. If the UI also rerenders the scroll
container during that sequence, the browser can reset horizontal scroll and
the user sees a snap back to the left edge.

This note captures the availability matrix fix and should be used for any
future touch interaction inside horizontally scrollable UI.

## Core rule

Preserve the physical scroll node and scroll position as first-class state.
Do not treat rerendering as free when the user is mid-tap inside a scroller.

## Rules

1. Capture scroll before `click`.

   Use `pointerdown` and `touchstart` to record `scrollLeft` and `scrollTop`.
   On mobile, the browser may adjust scroll or focus before the synthetic
   `click` event fires.

2. Do not replace the scroll node on first tap.

   If a tap only arms a control, mutate the existing button in place. Replacing
   `.cg-site-matrix-scroll` destroys the browser's scroll state and can produce
   snap-left behavior.

3. Do not clear state from the scroll handler.

   A tap can emit small scroll/focus side effects. A rule like "scroll cancels
   the armed cell" can undo the first tap before the second tap is possible.

4. Restore scroll across animation frames.

   Some browser correction happens after the click handler exits. Restore once
   immediately, then again in one or two `requestAnimationFrame` callbacks.

5. Keep tapped-cell layout stable.

   Long armed labels inside fixed-width cells create clipping or layout pressure.
   Prefer compact labels like `Book` over wider provider-specific text in the
   cell.

6. Clamp restored scroll.

   Layout may have changed since the snapshot. Clamp `scrollLeft` to
   `0..scrollWidth - clientWidth` and `scrollTop` to
   `0..scrollHeight - clientHeight`.

## Implementation pattern

```js
function captureScroll(scroll) {
  if (!(scroll instanceof HTMLElement)) return null;
  return {
    left: scroll.scrollLeft,
    top: scroll.scrollTop,
  };
}

function restoreScroll(scroll, snapshot) {
  if (!(scroll instanceof HTMLElement) || !snapshot) return;
  const maxLeft = Math.max(0, scroll.scrollWidth - scroll.clientWidth);
  const maxTop = Math.max(0, scroll.scrollHeight - scroll.clientHeight);
  scroll.scrollLeft = Math.min(Math.max(0, snapshot.left || 0), maxLeft);
  scroll.scrollTop = Math.min(Math.max(0, snapshot.top || 0), maxTop);
}

function restoreScrollAfterTap(scroll, snapshot) {
  restoreScroll(scroll, snapshot);
  requestAnimationFrame(() => {
    restoreScroll(scroll, snapshot);
    requestAnimationFrame(() => restoreScroll(scroll, snapshot));
  });
}

let pendingTapScroll = null;

root.addEventListener('pointerdown', (event) => {
  if (event.target.closest('[data-book-campsite-id]')) {
    pendingTapScroll = captureScroll(scroller);
  }
});

root.addEventListener('touchstart', (event) => {
  if (event.target.closest('[data-book-campsite-id]')) {
    pendingTapScroll = captureScroll(scroller);
  }
}, { passive: true });

root.addEventListener('click', (event) => {
  const button = event.target.closest('[data-book-campsite-id]');
  if (!button) return;

  event.preventDefault();
  armButtonInPlace(button);
  restoreScrollAfterTap(scroller, pendingTapScroll);
});
```

The important part is not the exact helpers. The important part is timing:
capture at `pointerdown`/`touchstart`, mutate in place on `click`, and restore
after the browser's own tap/focus work has had a chance to run.

## Regression tests

Test both the real mobile path and the platform-reset path.

For the real path, scroll the matrix horizontally, tap a visible cell with
`page.touchscreen().tap(x, y)`, wait two animation frames, and assert
`scrollLeft` is still near the original value.

For the platform-reset path, simulate the browser doing the worst thing:

```js
target.dispatchEvent(new PointerEvent('pointerdown', {
  bubbles: true,
  cancelable: true,
  pointerType: 'touch',
  clientX,
  clientY,
}));
matrix.scrollLeft = 0;
target.click();
```

Then wait two animation frames and assert the original horizontal scroll was
restored.

Also add a `MutationObserver` assertion for first tap:

```js
window.__matrixScrollNodeReplaced = false;
const matrix = document.querySelector('.cg-site-matrix-scroll');
const observer = new MutationObserver((records) => {
  for (const record of records) {
    for (const node of record.removedNodes) {
      if (node === matrix || node.contains?.(matrix)) {
        window.__matrixScrollNodeReplaced = true;
      }
    }
  }
});
observer.observe(host, { childList: true, subtree: true });
```

First tap should not replace the matrix scroll node.

## Common traps

- Capturing scroll during `click`, after the browser has already changed it.
- Rerendering the full table to change one cell label.
- Clearing armed state in `scroll` and making tap-induced scroll cancel tap state.
- Letting long labels resize or clip inside fixed-width cells.
- Testing only desktop mouse clicks, which do not reproduce mobile tap timing.
