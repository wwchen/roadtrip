import { modalTemplate } from './modal-template.js';

const STYLE_ID = 'rt-modal-styles';

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
  } = config;

  injectStyles();

  container.innerHTML = modalTemplate({ title, sheetOnMobile });

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

function injectStyles() {
  if (typeof document === 'undefined') return;
  if (document.getElementById(STYLE_ID)) return;
  const link = document.createElement('link');
  link.id = STYLE_ID;
  link.rel = 'stylesheet';
  link.href = '/web/design-system/modal.css';
  document.head.appendChild(link);
}
