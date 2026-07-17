// web/availability/watch-popover.js
//
// In-view shell for editing a single-day availability watch from the campground
// drawer. Trigger form state lives in watch-editor.js so this path and the
// topbar management path emit the same payload shape.

import {
  TRIGGER_KIND_ATC,
  TRIGGER_KIND_EMAIL_NOTIFY,
  TRIGGER_KIND_SLACK_NOTIFY,
  normalizeWatchCapabilities,
  mountWatchEditor,
} from './watch-editor.js';

/**
 * @param {HTMLElement} host
 * @param {object} args
 * @param {string} args.poiName
 * @param {string} args.date
 * @param {object} [args.watch]
 * @param {boolean} args.watching
 * @param {boolean} [args.stopWhenFound]
 * @param {boolean} [args.supportsAddToCart]
 * @param {object} [args.watchCapabilities]
 * @param {(payload: object) => Promise<void>} args.onSave
 * @param {() => Promise<void>} args.onRemove
 * @param {() => void} args.onClose
 */
export function mountWatchPopover(host, args) {
  const { poiName, date, watch, watching, supportsAddToCart, watchCapabilities, onSave, onRemove, onClose } = args;
  const capabilities = capabilitiesForEditor(watchCapabilities, supportsAddToCart);
  const controller = mountWatchEditor(host, {
    title: `Watch ${poiName}`,
    subtitle: dateLabel(date),
    watch: watchForEditor({ watch, watching, stopWhenFound: args.stopWhenFound, capabilities }),
    capabilities,
    onSave: async (payload) => {
      await onSave(payload);
      onClose();
    },
    onRemove: watching
      ? async () => {
        await onRemove();
        onClose();
      }
      : null,
    onClose,
  });

  function onDocClick(e) {
    if (host.contains(e.target)) return;
    onClose();
  }

  function onKey(e) {
    if (e.key === 'Escape') onClose();
  }

  setTimeout(() => {
    document.addEventListener('click', onDocClick);
    document.addEventListener('keydown', onKey);
  }, 0);

  return {
    dispose() {
      controller.dispose();
      document.removeEventListener('click', onDocClick);
      document.removeEventListener('keydown', onKey);
    },
  };
}

function watchForEditor({ watch, watching, stopWhenFound, capabilities }) {
  if (watching && watch) return watch;
  if (!watching) return null;
  return {
    trigger_kinds: defaultTriggerKindsForEditor(capabilities),
    trigger_config: {},
    stop_when_triggered: stopWhenFound !== false,
  };
}

function defaultTriggerKindsForEditor(capabilities) {
  const triggerKinds = [];
  if (capabilities.triggerKinds.has(TRIGGER_KIND_SLACK_NOTIFY)) triggerKinds.push(TRIGGER_KIND_SLACK_NOTIFY);
  if (capabilities.triggerKinds.has(TRIGGER_KIND_ATC)) triggerKinds.push(TRIGGER_KIND_ATC);
  return triggerKinds;
}

function capabilitiesForEditor(watchCapabilities, supportsAddToCart) {
  const normalized = normalizeWatchCapabilities(watchCapabilities);
  const triggerKinds = new Set();
  if (normalized.triggerKinds.has(TRIGGER_KIND_SLACK_NOTIFY) || normalized.triggerKinds.size === 0) {
    triggerKinds.add(TRIGGER_KIND_SLACK_NOTIFY);
  }
  if (normalized.triggerKinds.has(TRIGGER_KIND_EMAIL_NOTIFY)) triggerKinds.add(TRIGGER_KIND_EMAIL_NOTIFY);
  if (supportsAddToCart && (normalized.triggerKinds.has(TRIGGER_KIND_ATC) || normalized.triggerKinds.size === 0)) {
    triggerKinds.add(TRIGGER_KIND_ATC);
  }
  return { triggerKinds, bookingActions: normalized.bookingActions };
}

function dateLabel(date) {
  return new Date(`${date}T00:00:00Z`).toLocaleDateString('en-US', {
    weekday: 'short',
    month: 'short',
    day: 'numeric',
    timeZone: 'UTC',
  });
}
