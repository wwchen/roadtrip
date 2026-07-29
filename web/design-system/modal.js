import { modalTemplate } from './modal-template.js';

const STYLE_ID = 'rt-modal-styles';

/** Matches the breakpoint in modal.css that turns the card into a bottom sheet. */
const SHEET_MEDIA_QUERY = '(max-width: 560px)';
const DRAGGING_CLASS = 'rt-modal-sheet-dragging';
/** Drag far enough and it dismisses regardless of speed. */
const DRAG_DISMISS_DISTANCE_PX = 96;
/** A short fast flick also dismisses, provided it moved at all. */
const DRAG_DISMISS_VELOCITY_PX_PER_MS = 0.5;
const DRAG_MIN_FLICK_PX = 24;

/**
 * Mount a modal overlay into `container`.
 *
 * @param {Element} container - The host element that will receive the modal markup.
 * @param {{
 *   title?: string,
 *   sheetOnMobile?: boolean,
 *   onClose?: () => void,
 *   closeOnBackdrop?: boolean,
 * }} config
 * @returns {{ close(): void, setBody(el: Element): void, dispose(): void }}
 */
export function mountModal(container, config = {}) {
  const {
    title = '',
    sheetOnMobile = false,
    onClose,
    closeOnBackdrop = true,
    width,
  } = config;

  injectStyles();

  container.innerHTML = modalTemplate({ title, sheetOnMobile });

  // Optional wider modal (e.g. the two-column settings modal). The card reads
  // --rt-modal-width; setting it on the host cascades to the card.
  if (width && container.style && typeof container.style.setProperty === 'function') {
    container.style.setProperty('--rt-modal-width', width);
  }

  // ── DOM refs ──────────────────────────────────────────────────────────────
  // We use attribute selectors because querySelector may be unavailable in the
  // stub environment — all reads go through container.innerHTML in tests.
  // In a real browser, use querySelector for interactive features.
  let bodyHost = null;
  let previouslyFocused = null;

  // Grab a real body host when running in a real browser.
  if (typeof document !== 'undefined' && typeof document.querySelector === 'function') {
    bodyHost = container.querySelector('[data-modal-body]');
    previouslyFocused = document.activeElement;
    // Simple focus trap: move focus into the modal on open.
    const firstFocusable = container.querySelector(
      'button, [href], input, select, textarea, [tabindex]:not([tabindex="-1"])',
    );
    if (firstFocusable) firstFocusable.focus();
  }

  // ── Event handlers ────────────────────────────────────────────────────────
  function close() {
    if (typeof onClose === 'function') onClose();
  }

  function onClick(e) {
    // Header ✕ button
    if (e.target && e.target.closest && e.target.closest('[data-modal-close]')) {
      close();
      return;
    }
    // Scrim / backdrop click
    if (closeOnBackdrop && e.target && e.target.dataset && e.target.dataset.modalBackdrop !== undefined) {
      close();
    }
  }

  function onKeyDown(e) {
    if (e.key === 'Escape') close();
  }

  container.addEventListener('click', onClick);
  // Escape is global — must listen on document.
  if (typeof document !== 'undefined') {
    document.addEventListener('keydown', onKeyDown);
  }

  const disposeSheetDrag = setupSheetDrag(container, sheetOnMobile, close);

  // ── Public API ────────────────────────────────────────────────────────────
  return {
    /** Call onClose without removing the modal (caller decides lifecycle). */
    close,

    /**
     * Insert an element into the modal body host.
     * @param {Element} el
     */
    setBody(el) {
      if (bodyHost) {
        bodyHost.innerHTML = '';
        bodyHost.appendChild(el);
      }
    },

    /** Remove event listeners and clear the DOM. */
    dispose() {
      container.removeEventListener('click', onClick);
      if (disposeSheetDrag) disposeSheetDrag();
      if (typeof document !== 'undefined') {
        document.removeEventListener('keydown', onKeyDown);
      }
      // Restore focus to the element that was focused before the modal opened.
      if (previouslyFocused && typeof previouslyFocused.focus === 'function') {
        previouslyFocused.focus();
      }
      container.innerHTML = '';
    },
  };
}

/**
 * Drag-to-dismiss for the mobile bottom sheet.
 *
 * The grab handle is a promise: a bottom sheet that shows one is expected to
 * follow the finger and dismiss when flung down. Without this it is decoration,
 * and the affordance actively misleads.
 *
 * A drag may only start on `[data-modal-drag]` — the handle and the header. The
 * body scrolls, and starting a drag there would fight the scroller, which is the
 * failure mode `docs/touch-scroll-interactions.md` warns about.
 *
 * Returns a teardown function, or null when there is nothing to wire (not a
 * sheet, or no real DOM — the stub environment used by the tests).
 */
function setupSheetDrag(container, sheetOnMobile, close) {
  if (!sheetOnMobile) return null;
  if (typeof window === 'undefined') return null;
  if (!container || typeof container.querySelector !== 'function') return null;

  const card = container.querySelector('.rt-modal-sheet');
  if (!card || typeof card.addEventListener !== 'function') return null;

  let isDragging = false;
  let startY = 0;
  let startedAt = 0;
  let offsetY = 0;

  // Only drag while the CSS is actually rendering a sheet. Above the
  // breakpoint the card is centred and dragging it down means nothing.
  function isSheetLayout() {
    return typeof window.matchMedia === 'function' && window.matchMedia(SHEET_MEDIA_QUERY).matches;
  }

  function onPointerDown(e) {
    if (!isSheetLayout()) return;
    if (!e.target || typeof e.target.closest !== 'function') return;
    // The ✕ is a button; a press on it is a tap, not the start of a drag.
    if (e.target.closest('[data-modal-close]')) return;
    if (!e.target.closest('[data-modal-drag]')) return;

    isDragging = true;
    startY = e.clientY;
    startedAt = Date.now();
    offsetY = 0;
    card.classList.add(DRAGGING_CLASS);
    if (typeof card.setPointerCapture === 'function' && e.pointerId != null) {
      card.setPointerCapture(e.pointerId);
    }
  }

  function onPointerMove(e) {
    if (!isDragging) return;
    // Downward only. Dragging a bottom sheet up should not detach it from the
    // bottom edge.
    offsetY = Math.max(0, e.clientY - startY);
    card.style.transform = `translateY(${offsetY}px)`;
  }

  function onPointerUp() {
    if (!isDragging) return;
    isDragging = false;
    card.classList.remove(DRAGGING_CLASS);

    const elapsedMs = Math.max(1, Date.now() - startedAt);
    const velocity = offsetY / elapsedMs;
    const dismissed =
      offsetY > DRAG_DISMISS_DISTANCE_PX ||
      (offsetY > DRAG_MIN_FLICK_PX && velocity > DRAG_DISMISS_VELOCITY_PX_PER_MS);

    // Clear the inline transform either way: on dismissal the host is about to
    // be torn down, and on release the CSS transition animates the snap back.
    card.style.transform = '';
    if (dismissed) close();
  }

  card.addEventListener('pointerdown', onPointerDown);
  card.addEventListener('pointermove', onPointerMove);
  card.addEventListener('pointerup', onPointerUp);
  card.addEventListener('pointercancel', onPointerUp);

  return function disposeSheetDrag() {
    card.removeEventListener('pointerdown', onPointerDown);
    card.removeEventListener('pointermove', onPointerMove);
    card.removeEventListener('pointerup', onPointerUp);
    card.removeEventListener('pointercancel', onPointerUp);
  };
}

function injectStyles() {
  if (typeof document === 'undefined') return;
  if (document.getElementById(STYLE_ID)) return;
  const link = document.createElement('link');
  link.id = STYLE_ID;
  link.rel = 'stylesheet';
  link.href = '/web/design-system/modal.css';
  document.head.appendChild(link);
}
