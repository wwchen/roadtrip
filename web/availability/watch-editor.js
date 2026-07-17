// web/availability/watch-editor.js
//
// Shared trigger editor for availability watches. It stays intentionally small:
// callers own placement and persistence; this widget owns local form state and
// emits the watch trigger PATCH/create shape.

import { escapeHtml } from '../core.js';

export const TRIGGER_KIND_SLACK_NOTIFY = 'slack_notify';
export const TRIGGER_KIND_ATC = 'atc';

const WATCH_EDITOR_STYLE_ID = 'rt-watch-editor-styles';

/**
 * @param {HTMLElement} host
 * @param {object} args
 * @param {string} [args.title]
 * @param {string} [args.subtitle]
 * @param {object} [args.watch]
 * @param {object} [args.capabilities]
 * @param {(payload: object) => Promise<void>} args.onSave
 * @param {() => Promise<void>} [args.onRemove]
 * @param {() => void} [args.onClose]
 */
export function mountWatchEditor(host, args) {
  injectWatchEditorStyles();
  const capabilities = normalizeWatchCapabilities(args.capabilities);
  let state = initialState(args.watch, capabilities);

  function rerender() {
    host.innerHTML = renderEditor({ ...args, capabilities, state });
  }

  async function save() {
    const payload = buildTriggerPayload(state);
    if (payload.trigger_kinds.length === 0) {
      state = { ...state, error: 'Select at least one trigger.' };
      rerender();
      return;
    }
    state = { ...state, busy: true, error: null };
    rerender();
    try {
      await args.onSave(payload);
    } catch (err) {
      if (err?.name === 'AbortError') return;
      state = { ...state, busy: false, error: saveErrorMessage(err) };
      rerender();
    }
  }

  async function remove() {
    if (!args.onRemove) return;
    state = { ...state, busy: true, error: null };
    rerender();
    try {
      await args.onRemove();
    } catch (err) {
      if (err?.name === 'AbortError') return;
      state = { ...state, busy: false, error: saveErrorMessage(err) };
      rerender();
    }
  }

  function onClick(e) {
    const tgt = e.target;
    if (!(tgt instanceof Element)) return;
    if (tgt.closest('[data-watch-editor-close]')) {
      args.onClose?.();
      return;
    }
    if (tgt.closest('[data-watch-editor-save]')) {
      save();
      return;
    }
    if (tgt.closest('[data-watch-editor-remove]')) {
      remove();
    }
  }

  function onChange(e) {
    const tgt = e.target;
    if (!(tgt instanceof HTMLInputElement)) return;
    if (tgt.name === 'slack_notify') state = { ...state, slackNotify: tgt.checked, error: null };
    if (tgt.name === 'atc') state = { ...state, addToCart: tgt.checked, error: null };
    if (tgt.name === 'stop_when_triggered') state = { ...state, stopWhenTriggered: tgt.checked, error: null };
    rerender();
  }

  function onInput(e) {
    const tgt = e.target;
    if (!(tgt instanceof HTMLInputElement)) return;
    if (tgt.name === 'slack_channel') state = { ...state, slackChannel: tgt.value, error: null };
  }

  rerender();
  host.addEventListener('click', onClick);
  host.addEventListener('change', onChange);
  host.addEventListener('input', onInput);

  return {
    dispose() {
      host.removeEventListener('click', onClick);
      host.removeEventListener('change', onChange);
      host.removeEventListener('input', onInput);
    },
  };
}

export function normalizeWatchCapabilities(value) {
  const triggerKinds =
    value?.triggerKinds instanceof Set
      ? value.triggerKinds
      : new Set(Array.isArray(value?.trigger_kinds) ? value.trigger_kinds : []);
  const bookingActions =
    value?.bookingActions instanceof Set
      ? value.bookingActions
      : new Set(Array.isArray(value?.booking_actions) ? value.booking_actions : []);
  return { triggerKinds, bookingActions };
}

export function watchStopWhenTriggered(watch, fallback = true) {
  if (!watch) return fallback;
  const value = watch.stop_when_triggered ?? watch.stopWhenTriggered;
  return value == null ? fallback : Boolean(value);
}

export function watchSlackChannel(watch) {
  const config = watch?.trigger_config ?? watch?.triggerConfig ?? {};
  const nested = config?.[TRIGGER_KIND_SLACK_NOTIFY]?.channel;
  if (typeof nested === 'string' && nested.trim()) return nested;
  const legacy = config?.channel;
  return typeof legacy === 'string' && legacy.trim() ? legacy : '';
}

export function watchHasTrigger(watch, kind) {
  return Array.isArray(watch?.trigger_kinds) && watch.trigger_kinds.includes(kind);
}

export function buildTriggerPayload(state) {
  const triggerKinds = [];
  if (state.slackNotify) triggerKinds.push(TRIGGER_KIND_SLACK_NOTIFY);
  if (state.addToCart) triggerKinds.push(TRIGGER_KIND_ATC);
  const triggerConfig = {};
  const channel = String(state.slackChannel || '').trim();
  if (state.slackNotify && channel) {
    triggerConfig[TRIGGER_KIND_SLACK_NOTIFY] = { channel };
  }
  return {
    trigger_kinds: triggerKinds,
    trigger_config: triggerConfig,
    stop_when_triggered: !!state.stopWhenTriggered,
  };
}

function initialState(watch, capabilities) {
  const hasWatch = !!watch;
  return {
    slackNotify: hasWatch ? watchHasTrigger(watch, TRIGGER_KIND_SLACK_NOTIFY) : capabilities.triggerKinds.has(TRIGGER_KIND_SLACK_NOTIFY),
    addToCart: hasWatch ? watchHasTrigger(watch, TRIGGER_KIND_ATC) : false,
    stopWhenTriggered: watchStopWhenTriggered(watch, true),
    slackChannel: watchSlackChannel(watch),
    busy: false,
    error: null,
  };
}

function renderEditor({ title, subtitle, watch, capabilities, state, onRemove, onClose }) {
  const canSlack = capabilities.triggerKinds.has(TRIGGER_KIND_SLACK_NOTIFY) || state.slackNotify;
  const canAtc = capabilities.triggerKinds.has(TRIGGER_KIND_ATC);
  const showAtc = canAtc || state.addToCart;
  const busyAttr = state.busy ? 'disabled' : '';
  const errorHtml = state.error ? `<div class="rt-watch-editor-error">${escapeHtml(state.error)}</div>` : '';
  const closeHtml = onClose
    ? '<button type="button" class="rt-watch-editor-icon" data-watch-editor-close aria-label="Close">x</button>'
    : '';
  const removeHtml = onRemove
    ? `<button type="button" class="rt-watch-editor-remove" data-watch-editor-remove ${busyAttr}>Remove</button>`
    : '';
  const slackHtml = canSlack
    ? `
      ${toggleRow({
        name: 'slack_notify',
        title: 'Slack',
        help: 'Post when a matching site opens.',
        checked: state.slackNotify,
        disabled: state.busy,
      })}
      ${state.slackNotify ? `
        <label class="rt-watch-editor-field">
          <span>Channel override</span>
          <input type="text" name="slack_channel" value="${escapeHtml(state.slackChannel)}" placeholder="#camping" ${busyAttr}>
        </label>
      ` : ''}
    `
    : '';
  const atcHtml = showAtc
    ? toggleRow({
      name: 'atc',
      title: 'Add to cart',
      help: canAtc ? 'Try to hold a matching site.' : 'Unavailable for this watch scope.',
      checked: state.addToCart,
      disabled: state.busy || (!canAtc && !state.addToCart),
    })
    : '';

  return `
    <div class="rt-watch-editor" role="group" aria-label="Availability watch editor">
      <div class="rt-watch-editor-head">
        <div>
          ${title ? `<div class="rt-watch-editor-title">${escapeHtml(title)}</div>` : ''}
          ${subtitle ? `<div class="rt-watch-editor-subtitle">${escapeHtml(subtitle)}</div>` : ''}
        </div>
        ${closeHtml}
      </div>
      <div class="rt-watch-editor-body">
        ${slackHtml}
        ${atcHtml}
        ${toggleRow({
          name: 'stop_when_triggered',
          title: 'Stop when triggered',
          help: 'Mark done after a successful trigger.',
          checked: state.stopWhenTriggered,
          disabled: state.busy,
        })}
      </div>
      ${errorHtml}
      <div class="rt-watch-editor-actions">
        ${removeHtml}
        <button type="button" class="rt-watch-editor-save" data-watch-editor-save ${busyAttr}>${watch ? 'Save' : 'Set watch'}</button>
      </div>
    </div>
  `;
}

function toggleRow({ name, title, help, checked, disabled }) {
  return `
    <label class="rt-watch-editor-toggle">
      <span class="rt-watch-editor-toggle-text">
        <span class="rt-watch-editor-toggle-title">${escapeHtml(title)}</span>
        <span class="rt-watch-editor-toggle-help">${escapeHtml(help)}</span>
      </span>
      <span class="rt-watch-editor-switch">
        <input type="checkbox" name="${escapeHtml(name)}" ${checked ? 'checked' : ''} ${disabled ? 'disabled' : ''}>
        <span class="rt-watch-editor-switch-track" aria-hidden="true"></span>
      </span>
    </label>
  `;
}

function saveErrorMessage(err) {
  const body = typeof err?.body === 'string' ? err.body : '';
  if (body.includes('unsupported_trigger')) return 'Add to cart is not available for this watch.';
  if (body.includes('invalid_trigger_config')) return 'Check the trigger settings and try again.';
  return 'Could not save. Try again.';
}

function injectWatchEditorStyles() {
  if (document.getElementById(WATCH_EDITOR_STYLE_ID)) return;
  const css = `
  .rt-watch-editor {
    min-width: 240px;
    color: var(--rt-text);
    background: var(--rt-panel, #fff);
    border: 1px solid var(--rt-border);
    border-radius: 8px;
    box-shadow: 0 12px 32px rgba(0,0,0,0.18);
    padding: 12px;
    font-size: 12px;
  }
  .rt-watch-editor-head {
    display: flex;
    align-items: flex-start;
    justify-content: space-between;
    gap: 12px;
    margin-bottom: 8px;
  }
  .rt-watch-editor-title { font-weight: 700; font-size: 13px; }
  .rt-watch-editor-subtitle { color: var(--rt-muted); margin-top: 2px; }
  .rt-watch-editor-icon {
    width: 24px; height: 24px;
    border: 0; background: transparent; color: var(--rt-muted);
    border-radius: 4px; cursor: pointer;
  }
  .rt-watch-editor-icon:hover { background: var(--rt-fill-hover); color: var(--rt-text); }
  .rt-watch-editor-body { display: grid; gap: 8px; }
  .rt-watch-editor-toggle {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 12px;
  }
  .rt-watch-editor-toggle-text { display: grid; gap: 2px; min-width: 0; }
  .rt-watch-editor-toggle-title { font-weight: 600; }
  .rt-watch-editor-toggle-help { color: var(--rt-muted); font-size: 11px; line-height: 1.25; }
  .rt-watch-editor-switch { position: relative; display: inline-grid; flex: 0 0 auto; }
  .rt-watch-editor-switch input { position: absolute; opacity: 0; pointer-events: none; }
  .rt-watch-editor-switch-track {
    width: 34px; height: 18px; border-radius: 999px;
    background: var(--rt-border); transition: background 120ms ease;
  }
  .rt-watch-editor-switch-track::after {
    content: "";
    display: block;
    width: 14px; height: 14px; margin: 2px;
    border-radius: 50%;
    background: #fff;
    box-shadow: 0 1px 2px rgba(0,0,0,0.2);
    transition: transform 120ms ease;
  }
  .rt-watch-editor-switch input:checked + .rt-watch-editor-switch-track { background: var(--rt-brand); }
  .rt-watch-editor-switch input:checked + .rt-watch-editor-switch-track::after { transform: translateX(16px); }
  .rt-watch-editor-switch input:disabled + .rt-watch-editor-switch-track { opacity: 0.55; }
  .rt-watch-editor-field {
    display: grid;
    gap: 4px;
    color: var(--rt-muted);
    font-size: 11px;
  }
  .rt-watch-editor-field input {
    width: 100%;
    box-sizing: border-box;
    border: 1px solid var(--rt-border);
    border-radius: 6px;
    padding: 7px 8px;
    color: var(--rt-text);
    background: var(--rt-bg, #fff);
    font: inherit;
  }
  .rt-watch-editor-error {
    margin-top: 8px;
    color: var(--rt-error);
    font-size: 11px;
  }
  .rt-watch-editor-actions {
    display: flex;
    justify-content: flex-end;
    gap: 8px;
    margin-top: 10px;
  }
  .rt-watch-editor-save,
  .rt-watch-editor-remove {
    border-radius: 6px;
    padding: 7px 10px;
    font: inherit;
    font-weight: 600;
    cursor: pointer;
  }
  .rt-watch-editor-save {
    border: 1px solid var(--rt-brand);
    background: var(--rt-brand);
    color: #fff;
  }
  .rt-watch-editor-remove {
    border: 1px solid var(--rt-border);
    background: transparent;
    color: var(--rt-error);
  }
  .rt-watch-editor-save:disabled,
  .rt-watch-editor-remove:disabled { opacity: 0.6; cursor: wait; }
  `;
  const tag = document.createElement('style');
  tag.id = WATCH_EDITOR_STYLE_ID;
  tag.textContent = css;
  document.head.appendChild(tag);
}
