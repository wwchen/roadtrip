// Drawer DOM lifecycle: mount, show/hide, drag-to-dismiss, map-empty-click,
// and the delegated per-POI Directions button handler.
//
// Mobile: bottom sheet with two snap states (60% half / 90% full) using dvh
// units + visualViewport.resize so iOS Safari URL-bar collapse doesn't break
// height. Desktop: right-side panel ~420px wide.
//
// Pin reselect while drawer open: opacity-only fade (~150ms), DOM stable,
// skeleton overlay during fetch. Inflight fetch is cancelled by closing or
// re-opening (see beginSession).

import { state } from '../core.js';

export const DRAWER_ROOT_ID = 'cg-drawer';
export const BACKDROP_ID = 'cg-drawer-backdrop';

let openController = null;   // AbortController for inflight fetch on the open drawer
let activeFeature = null;    // currently displayed feature (used for stale-check)

/**
 * Begin a new drawer session. Aborts any in-flight fetch from a prior
 * session, mints a fresh AbortController, and records the active feature
 * so category renderers can stale-check responses against `isActiveFeature`.
 *
 * Returns the new AbortSignal — pass it to fetch() for automatic cancel
 * on close / pin-reselect.
 */
export function beginSession(feature) {
  if (openController) openController.abort();
  openController = new AbortController();
  activeFeature = feature ?? null;
  return openController.signal;
}

/** True when `feature` is still the one the user is looking at. */
export function isActiveFeature(feature) {
  return activeFeature === feature;
}

/** Restart the AbortController without changing the active feature.
 *  Used by the campground "Retry" affordance. */
export function restartController() {
  if (openController) openController.abort();
  openController = new AbortController();
  return openController.signal;
}

/**
 * Generic drawer entry point. Caller provides ready-to-mount HTML; the
 * drawer module owns shell, animation, dismissal, and pin-reselect plumbing.
 * Optional onMounted is invoked after the content is in the DOM (use it for
 * fetches that fill in placeholders — e.g. supercharger pricing).
 */
export function openDrawer(contentHtml, onMounted) {
  ensureDrawerDOM();
  const root = document.getElementById(DRAWER_ROOT_ID);

  const signal = beginSession(null);

  const content = document.querySelector(`#${DRAWER_ROOT_ID} .cg-drawer-content`);
  content.innerHTML = contentHtml;
  show();
  if (typeof onMounted === 'function') onMounted(signal);

  root.querySelector('.cg-drawer-close')?.addEventListener('click', closeDrawer);
  attachDragHandlers(root);
}

/** Public close. Safe to call when the drawer isn't open. */
export function closeDrawer() {
  if (openController) {
    openController.abort();
    openController = null;
  }
  activeFeature = null;
  const root = document.getElementById(DRAWER_ROOT_ID);
  const backdrop = document.getElementById(BACKDROP_ID);
  if (!root?.classList.contains('open') && !backdrop?.classList.contains('open')) return;
  root?.classList.remove('open', 'full');
  backdrop?.classList.remove('open');
  setTimeout(() => {
    if (root) root.style.display = 'none';
    if (backdrop) backdrop.style.display = 'none';
  }, 220);
}

export function show() {
  const root = document.getElementById(DRAWER_ROOT_ID);
  const backdrop = document.getElementById(BACKDROP_ID);
  if (root) {
    root.style.display = 'flex';
    requestAnimationFrame(() => root.classList.add('open'));
  }
  if (backdrop) {
    backdrop.style.display = 'block';
    requestAnimationFrame(() => backdrop.classList.add('open'));
  }
}

/**
 * Build the drawer DOM once and reuse. Sibling of #map at body level so
 * MapLibre gestures pass through the (pointer-events:none) backdrop.
 */
export function ensureDrawerDOM() {
  if (document.getElementById(DRAWER_ROOT_ID)) return;

  const backdrop = document.createElement('div');
  backdrop.id = BACKDROP_ID;
  backdrop.className = 'cg-drawer-backdrop';
  document.body.appendChild(backdrop);

  const root = document.createElement('aside');
  root.id = DRAWER_ROOT_ID;
  root.className = 'cg-drawer';
  root.setAttribute('role', 'dialog');
  root.setAttribute('aria-label', 'Pin details');
  root.innerHTML = `
    <div class="cg-drawer-handle" aria-hidden="true"></div>
    <button class="cg-drawer-close" aria-label="Close">&times;</button>
    <div class="cg-drawer-content"></div>
  `;
  document.body.appendChild(root);

  // Backdrop is intentionally pointer-events:none — MapLibre still needs to
  // receive pan/zoom gestures even with the drawer open. Instead, listen
  // for a map click that misses every interactive POI layer and treat it
  // as "click outside POI → close drawer." Layer-specific click handlers
  // run before this catch-all because we use queryRenderedFeatures to test.
  if (state.map) {
    const POI_LAYERS = ['cg-points-hit', 'sc-points-hit', 'pf-points-hit', 'np-pts-hit', 'sp-pts-hit', 'np-fill', 'sp-fill'];
    state.map.on('click', (e) => {
      if (!root.classList.contains('open')) return;
      const present = POI_LAYERS.filter(id => state.map.getLayer(id));
      const hits = present.length ? state.map.queryRenderedFeatures(e.point, { layers: present }) : [];
      if (!hits.length) {
        closeDrawer();
        // Also drop any sticky browse-mode pin (row 0 + "A" marker).
        if (typeof window.__rtClearBrowsePin === 'function') window.__rtClearBrowsePin();
      }
    });
  }

  // Delegated handler for the per-POI Directions button. Bound once at DOM
  // creation so it survives the innerHTML rewrites in renderShell/openDrawer.
  // Routes through window.__rtAddTripStop (set up by topbar.js) so the
  // drawer doesn't depend on the topbar module.
  root.querySelector('.cg-drawer-content').addEventListener('click', (e) => {
    const btn = e.target.closest?.('.rt-poi-directions');
    if (!btn) return;
    e.preventDefault();
    const lng = Number(btn.dataset.lng);
    const lat = Number(btn.dataset.lat);
    if (!Number.isFinite(lng) || !Number.isFinite(lat)) return;
    if (typeof window.__rtAddTripStop !== 'function') return;
    window.__rtAddTripStop({
      name: btn.dataset.name || 'Selected place',
      lng, lat,
      kind: btn.dataset.kind || 'PLACE',
    });
    closeDrawer();
  });

  // iOS Safari: visualViewport.resize fires on URL-bar collapse, keyboard,
  // and zoom. Filter for URL-bar (large height delta, no zoom change).
  if (window.visualViewport) {
    let lastH = window.visualViewport.height;
    window.visualViewport.addEventListener('resize', () => {
      const dh = Math.abs(window.visualViewport.height - lastH);
      // Only react to substantial changes — small deltas are zoom artifacts.
      if (dh > 40) {
        lastH = window.visualViewport.height;
        // CSS dvh handles the rest; this is a hook for future fine-tuning.
      }
    });
  }
}

/** Drag-past-30% dismissal.
 *
 * Two start regions:
 *   - the handle (always; widely-spaced 40px-tall hitbox via CSS),
 *   - the drawer body, but ONLY when scrolled to the top. Once the user has
 *     scrolled the drawer content, vertical touches are scroll, not dismiss.
 *
 * This is the standard iOS bottom-sheet pattern: small-handle gesture for
 * deliberate dismiss, body-gesture as a discoverability shortcut.
 */
export function attachDragHandlers(root) {
  if (root.dataset.dragWired) return;
  root.dataset.dragWired = '1';

  const handle = root.querySelector('.cg-drawer-handle');

  let startY = 0;
  let startH = 0;
  // 'pending' = touch started but we haven't decided drag-vs-scroll yet
  // 'handle'  = drag began on the grab bar; always tracks
  // 'body'    = drag began in the body and exceeded the slop threshold
  //             downward while at scrollTop=0 — we own the gesture
  let phase = null;
  // Pixels the user must travel before a body touch becomes a drag. Below
  // this, we let the browser interpret the touch as a tap or scroll.
  const SLOP = 8;

  // Records whether the touch began on an interactive element (link,
  // button, input). We can't reject those at touchstart — the user
  // needs to be able to drag-from-anywhere — but if motion stays below
  // the slop threshold we let the tap through unimpeded.
  let startedOnInteractive = false;

  function onStart(e, originatedAtHandle) {
    if (e.touches.length !== 1) return;
    startY = e.touches[0].clientY;
    startH = root.getBoundingClientRect().height;
    startedOnInteractive =
      !originatedAtHandle && !!e.target.closest('a, button, input, select, textarea');
    phase = originatedAtHandle ? 'handle' : 'pending';
  }

  function onMove(e) {
    if (phase == null) return;
    const dy = e.touches[0].clientY - startY;

    // Pending body touch → decide whether this is a drag or a scroll.
    if (phase === 'pending') {
      if (dy > SLOP) {
        // Body drag exceeded slop. If content is scrolled mid-drawer,
        // hand back to the native scroll. Otherwise we own the gesture
        // even when it started over a link or button — the user is
        // clearly swiping, not tapping.
        if (root.scrollTop > 0) {
          phase = null;
          return;
        }
        phase = 'body';
      } else if (dy < -SLOP || root.scrollTop > 0) {
        // User wants to scroll; release the gesture entirely.
        phase = null;
        return;
      } else {
        return; // not enough motion yet — let any tap through
      }
    }

    if (phase === 'body' && dy < 0) {
      // Drag flipped upward; hand back to native scroll.
      root.style.height = '';
      phase = null;
      return;
    }
    // Active drag — claim the gesture so iOS doesn't rubber-band the body.
    if (e.cancelable) e.preventDefault();
    if (dy > 0) {
      const newH = Math.max(0, startH - dy);
      root.style.height = `${newH}px`;
    } else if (phase === 'handle') {
      root.classList.add('full');
    }
  }

  function onEnd(e) {
    if (phase == null) return;
    const dy = e.changedTouches[0].clientY - startY;
    const dragged = (dy / startH) * 100;
    root.style.height = '';
    if (phase !== 'pending') {
      if (dragged > 30) {
        closeDrawer();
      } else if (phase === 'handle' && dy < -50) {
        root.classList.add('full');
      } else if (phase === 'handle') {
        root.classList.remove('full');
      }
    }
    phase = null;
  }

  if (handle) {
    handle.addEventListener('touchstart', (e) => onStart(e, true), { passive: true });
  }
  root.addEventListener('touchstart', (e) => {
    if (handle && handle.contains(e.target)) return;
    onStart(e, false);
  }, { passive: true });
  // Non-passive: we need preventDefault to claim the gesture from iOS scroll.
  root.addEventListener('touchmove', onMove, { passive: false });
  root.addEventListener('touchend', onEnd);
  root.addEventListener('touchcancel', onEnd);
}
