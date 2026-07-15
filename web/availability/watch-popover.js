// web/availability/watch-popover.js
//
// In-view popover for setting or removing an availability watch on a single
// day for a POI. Opened from a reserved (non-available) matrix cell in the
// campground drawer. The backend watch is POI-level and single-day.
//
// Today this is a quick confirm with one trigger option. The body is laid out
// so future trigger configuration (selectable Slack channel, an ATC action
// alongside Slack) can be added without a redesign.
//
// Pure-ish widget: it owns its own busy/error state and re-renders in place;
// the parent (availability-week.js) supplies onSet / onRemove / onClose and
// owns the authoritative watch cache.

import { escapeHtml } from '../core.js';

/**
 * @param {HTMLElement} host
 * @param {object}   args
 * @param {string}   args.poiName
 * @param {string}   args.date          YYYY-MM-DD (the watched day).
 * @param {boolean}  args.watching      Whether a watch already exists.
 * @param {boolean}  [args.stopWhenFound]
 * @param {boolean}  [args.supportsAddToCart]
 * @param {(options: { stopWhenFound: boolean, addToCart: boolean }) => Promise<void>} args.onSet
 * @param {() => Promise<void>} args.onRemove
 * @param {() => void}          args.onClose
 */
export function mountWatchPopover(host, args) {
  const { poiName, date, onSet, onRemove, onClose } = args;
  const supportsAddToCart = !!args.supportsAddToCart;
  let state = {
    watching: !!args.watching,
    stopWhenFound: args.stopWhenFound !== false,
    addToCart: false,
    busy: false,
    error: null,
  };

  function rerender() {
    host.innerHTML = renderPopover({ poiName, date, supportsAddToCart, ...state });
  }

  async function onClick(e) {
    const tgt = e.target;
    if (!(tgt instanceof Element)) return;
    if (tgt.closest('.cg-watch-pop-close')) {
      onClose();
      return;
    }
    const action = tgt.closest('.cg-watch-pop-action');
    if (!action || action.disabled) return;
    state = { ...state, busy: true, error: null };
    rerender();
    try {
      if (state.watching) {
        await onRemove?.();
        state = { ...state, watching: false, busy: false, error: null };
      } else {
        await onSet?.({ stopWhenFound: state.stopWhenFound, addToCart: state.addToCart });
        state = { ...state, watching: true, busy: false, error: null };
      }
      rerender();
    } catch (err) {
      if (err?.name === 'AbortError') return;
      state = { ...state, busy: false, error: saveErrorMessage(err) };
      rerender();
    }
  }

  function onDocClick(e) {
    if (host.contains(e.target)) return;
    onClose();
  }

  function onChange(e) {
    const tgt = e.target;
    if (!(tgt instanceof HTMLInputElement)) return;
    if (tgt.classList.contains('cg-watch-pop-stop')) {
      state = { ...state, stopWhenFound: tgt.checked, error: null };
      return;
    }
    if (tgt.classList.contains('cg-watch-pop-atc')) {
      state = { ...state, addToCart: tgt.checked, error: null };
    }
  }

  function onKey(e) {
    if (e.key === 'Escape') onClose();
  }

  rerender();
  host.addEventListener('click', onClick);
  host.addEventListener('change', onChange);
  // Defer document listeners a tick so the opening click doesn't close it.
  setTimeout(() => {
    document.addEventListener('click', onDocClick);
    document.addEventListener('keydown', onKey);
  }, 0);

  return {
    dispose() {
      host.removeEventListener('click', onClick);
      host.removeEventListener('change', onChange);
      document.removeEventListener('click', onDocClick);
      document.removeEventListener('keydown', onKey);
    },
  };
}

function renderPopover({ poiName, date, watching, stopWhenFound, addToCart, supportsAddToCart, busy, error }) {
  const dateLabel = new Date(`${date}T00:00:00Z`).toLocaleDateString('en-US', {
    weekday: 'short',
    month: 'short',
    day: 'numeric',
    timeZone: 'UTC',
  });
  const actionLabel = busy
    ? watching
      ? 'Removing…'
      : 'Setting…'
    : watching
      ? 'Watching — tap to remove'
      : 'Set watch';
  const actionClass = watching ? 'cg-btn-secondary' : 'cg-btn-primary';
  const errorHtml = error ? `<div class="cg-watch-pop-error">${escapeHtml(error)}</div>` : '';
  const stopWhenFoundHtml = watching ? '' : `
      <label class="cg-watch-pop-option">
        <span class="cg-watch-pop-option-text">
          <span class="cg-watch-pop-option-title">Stop when found</span>
          <span class="cg-watch-pop-option-help">Turn this watch off after the first alert.</span>
        </span>
        <span class="cg-watch-pop-switch">
          <input
            type="checkbox"
            class="cg-watch-pop-stop"
            ${stopWhenFound ? 'checked' : ''}
            ${busy ? 'disabled' : ''}
          >
          <span class="cg-watch-pop-switch-track" aria-hidden="true"></span>
        </span>
      </label>`;
  const addToCartHtml = watching || !supportsAddToCart ? '' : `
      <label class="cg-watch-pop-option">
        <span class="cg-watch-pop-option-text">
          <span class="cg-watch-pop-option-title">Add to cart</span>
          <span class="cg-watch-pop-option-help">Try to hold one matching site when it opens.</span>
        </span>
        <span class="cg-watch-pop-switch">
          <input
            type="checkbox"
            class="cg-watch-pop-atc"
            ${addToCart ? 'checked' : ''}
            ${busy ? 'disabled' : ''}
          >
          <span class="cg-watch-pop-switch-track" aria-hidden="true"></span>
        </span>
      </label>`;
  return `
    <div class="cg-watch-pop" role="dialog" aria-label="Availability watch">
      <div class="cg-watch-pop-head">
        <div class="cg-watch-pop-title">Watch ${escapeHtml(poiName)}</div>
        <button type="button" class="cg-watch-pop-close" aria-label="Close">×</button>
      </div>
      <div class="cg-watch-pop-date">${escapeHtml(dateLabel)}</div>
      ${stopWhenFoundHtml}
      ${addToCartHtml}
      <button
        type="button"
        class="cg-btn ${actionClass} cg-watch-pop-action"
        data-state="${watching ? 'watching' : 'set'}"
        ${busy ? 'disabled' : ''}
      >${escapeHtml(actionLabel)}</button>
      ${errorHtml}
      <div class="cg-watch-pop-note">🔔 Alerts post to Slack when a site opens.</div>
    </div>
  `;
}

function saveErrorMessage(err) {
  const body = typeof err?.body === 'string' ? err.body : '';
  return body.includes('unsupported_trigger')
    ? 'Add to cart is no longer available for this watch.'
    : 'Could not save. Try again.';
}
