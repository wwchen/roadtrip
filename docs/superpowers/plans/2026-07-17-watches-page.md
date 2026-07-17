# Watches Page Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a full-page `/watches` route with reusable design-system primitives and watch-specific components, replacing the topbar dropdown as the primary watch management UI.

**Architecture:** Two-layer component system — generic design-system primitives (`web/design-system/`) composed into watch-specific components (`web/watches/`). Page wires them together with URL param handling for deep-links from Slack/email/UI.

**Tech Stack:** Vanilla JS (no framework), CSS custom properties (`--rt-*` tokens), component pattern: `mount(container, config) → { dispose() }`

## Global Constraints

- All CSS uses `--rt-*` design tokens from `web/design-system/tokens.css`
- Components are functions: `mount(container, config) → { dispose(), update?() }`
- No framework dependencies — plain DOM manipulation
- Follow existing patterns in `web/availability/watch-editor.js` for style injection and event delegation
- Imports use relative paths with `.js` extension
- Use `escapeHtml` from `web/core.js` for all user-provided text rendered as HTML

---

## File Structure

**Design System Primitives** (new files in `web/design-system/`):
- `web/design-system/banner.js` — dismissible notification bar (success/error/info)
- `web/design-system/banner.css` — banner styles
- `web/design-system/toggle-switch.js` — on/off toggle with label
- `web/design-system/toggle-switch.css` — toggle styles
- `web/design-system/double-confirm-button.js` — two-click destructive action
- `web/design-system/double-confirm-button.css` — double-confirm styles
- `web/design-system/data-table.js` — generic table renderer from column defs + rows
- `web/design-system/data-table.css` — table styles (extends existing `.data-table` from catalog.css)
- `web/design-system/form-section.js` — label + input + help text group
- `web/design-system/form-section.css` — form section styles

**Watch Components** (new directory `web/watches/`):
- `web/watches/trigger-selector.js` — Slack/Email toggles + "stop when triggered"
- `web/watches/watch-form.js` — create/edit form composing FormSection + TriggerSelector
- `web/watches/watch-table.js` — table with status/actions composing DataTable + DoubleConfirmButton
- `web/watches/watches-page.js` — page controller wiring form + table + banner + URL params

**Page** (new file at project root):
- `watches.html` — HTML shell linking tokens.css, loading watches-page.js

---

### Task 1: Design System — Banner

**Files:**
- Create: `web/design-system/banner.js`
- Create: `web/design-system/banner.css`

**Interfaces:**
- Consumes: nothing
- Produces: `mountBanner(container, { type, message, dismissable?, onDismiss? }) → { dispose(), update({ type, message }) }`
  - `type`: `'success' | 'error' | 'info'`
  - `message`: string (plain text, escaped internally)
  - `dismissable`: boolean, default true
  - `onDismiss`: callback when X clicked

- [ ] **Step 1: Create banner.css**

```css
/* web/design-system/banner.css */
.rt-banner {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 14px;
  border-radius: var(--rt-r-md);
  font-size: 13px;
  font-weight: 500;
}

.rt-banner-success {
  background: rgba(76, 185, 106, 0.12);
  border: 1px solid var(--rt-success);
  color: var(--rt-success);
}

.rt-banner-error {
  background: rgba(245, 101, 101, 0.12);
  border: 1px solid var(--rt-error);
  color: var(--rt-error);
}

.rt-banner-info {
  background: var(--rt-brand-tint);
  border: 1px solid var(--rt-brand);
  color: var(--rt-brand);
}

.rt-banner-message { flex: 1; min-width: 0; }

.rt-banner-dismiss {
  flex-shrink: 0;
  width: 24px;
  height: 24px;
  display: grid;
  place-items: center;
  background: transparent;
  border: 0;
  border-radius: var(--rt-r-sm);
  color: inherit;
  cursor: pointer;
  opacity: 0.7;
}

.rt-banner-dismiss:hover { opacity: 1; background: rgba(255,255,255,0.06); }
```

- [ ] **Step 2: Create banner.js**

```js
// web/design-system/banner.js
import { escapeHtml } from '../core.js';

const STYLE_ID = 'rt-banner-styles';

export function mountBanner(container, config) {
  injectStyles();
  let state = { type: config.type, message: config.message };

  function render() {
    const dismissHtml = config.dismissable !== false
      ? '<button type="button" class="rt-banner-dismiss" aria-label="Dismiss">&times;</button>'
      : '';
    container.innerHTML = `
      <div class="rt-banner rt-banner-${escapeHtml(state.type)}" role="alert">
        <span class="rt-banner-message">${escapeHtml(state.message)}</span>
        ${dismissHtml}
      </div>
    `;
  }

  function onClick(e) {
    if (e.target.closest('.rt-banner-dismiss')) {
      container.innerHTML = '';
      config.onDismiss?.();
    }
  }

  render();
  container.addEventListener('click', onClick);

  return {
    update({ type, message }) {
      state = { type, message };
      render();
    },
    dispose() {
      container.removeEventListener('click', onClick);
      container.innerHTML = '';
    },
  };
}

function injectStyles() {
  if (document.getElementById(STYLE_ID)) return;
  const link = document.createElement('link');
  link.id = STYLE_ID;
  link.rel = 'stylesheet';
  link.href = '/web/design-system/banner.css';
  document.head.appendChild(link);
}
```

- [ ] **Step 3: Verify in browser**

Open a scratch HTML file or the dev server and confirm the banner renders with each type (success, error, info), dismiss works, and tokens resolve correctly.

- [ ] **Step 4: Commit**

```bash
git add web/design-system/banner.js web/design-system/banner.css
git commit -m "feat(design-system): add Banner component"
```

---

### Task 2: Design System — ToggleSwitch

**Files:**
- Create: `web/design-system/toggle-switch.js`
- Create: `web/design-system/toggle-switch.css`

**Interfaces:**
- Consumes: nothing
- Produces: `mountToggleSwitch(container, { name, label, help?, checked, disabled?, onChange }) → { dispose(), update({ checked, disabled? }) }`
  - `onChange(checked: boolean)` called on toggle

- [ ] **Step 1: Create toggle-switch.css**

Extract the toggle styles from `watch-editor.js` into a standalone CSS file:

```css
/* web/design-system/toggle-switch.css */
.rt-toggle {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.rt-toggle-text { display: grid; gap: 2px; min-width: 0; }
.rt-toggle-label { font-size: 12px; font-weight: 600; color: var(--rt-text); }
.rt-toggle-help { font-size: 11px; line-height: 1.25; color: var(--rt-muted); }

.rt-toggle-switch { position: relative; display: inline-grid; flex: 0 0 auto; cursor: pointer; }
.rt-toggle-switch input { position: absolute; opacity: 0; pointer-events: none; }

.rt-toggle-track {
  width: 34px;
  height: 18px;
  border-radius: var(--rt-r-pill);
  background: var(--rt-border-strong);
  transition: background 120ms ease;
}

.rt-toggle-track::after {
  content: "";
  display: block;
  width: 14px;
  height: 14px;
  margin: 2px;
  border-radius: 50%;
  background: #fff;
  box-shadow: 0 1px 2px rgba(0,0,0,0.2);
  transition: transform 120ms ease;
}

.rt-toggle-switch input:checked + .rt-toggle-track { background: var(--rt-brand); }
.rt-toggle-switch input:checked + .rt-toggle-track::after { transform: translateX(16px); }
.rt-toggle-switch input:disabled + .rt-toggle-track { opacity: 0.55; cursor: default; }
```

- [ ] **Step 2: Create toggle-switch.js**

```js
// web/design-system/toggle-switch.js
import { escapeHtml } from '../core.js';

const STYLE_ID = 'rt-toggle-switch-styles';

export function mountToggleSwitch(container, config) {
  injectStyles();
  let state = { checked: !!config.checked, disabled: !!config.disabled };

  function render() {
    const helpHtml = config.help
      ? `<span class="rt-toggle-help">${escapeHtml(config.help)}</span>`
      : '';
    container.innerHTML = `
      <label class="rt-toggle">
        <span class="rt-toggle-text">
          <span class="rt-toggle-label">${escapeHtml(config.label)}</span>
          ${helpHtml}
        </span>
        <span class="rt-toggle-switch">
          <input type="checkbox" name="${escapeHtml(config.name)}" ${state.checked ? 'checked' : ''} ${state.disabled ? 'disabled' : ''}>
          <span class="rt-toggle-track" aria-hidden="true"></span>
        </span>
      </label>
    `;
  }

  function onChange(e) {
    if (e.target.name !== config.name) return;
    state = { ...state, checked: e.target.checked };
    config.onChange?.(state.checked);
  }

  render();
  container.addEventListener('change', onChange);

  return {
    update({ checked, disabled }) {
      state = { checked: !!checked, disabled: disabled != null ? !!disabled : state.disabled };
      render();
    },
    dispose() {
      container.removeEventListener('change', onChange);
      container.innerHTML = '';
    },
  };
}

function injectStyles() {
  if (document.getElementById(STYLE_ID)) return;
  const link = document.createElement('link');
  link.id = STYLE_ID;
  link.rel = 'stylesheet';
  link.href = '/web/design-system/toggle-switch.css';
  document.head.appendChild(link);
}
```

- [ ] **Step 3: Commit**

```bash
git add web/design-system/toggle-switch.js web/design-system/toggle-switch.css
git commit -m "feat(design-system): add ToggleSwitch component"
```

---

### Task 3: Design System — DoubleConfirmButton

**Files:**
- Create: `web/design-system/double-confirm-button.js`
- Create: `web/design-system/double-confirm-button.css`

**Interfaces:**
- Consumes: nothing
- Produces: `mountDoubleConfirmButton(container, { label, confirmLabel?, onConfirm, armedTimeoutMs? }) → { dispose(), disarm() }`
  - `label`: text for idle state (e.g. "Delete")
  - `confirmLabel`: text for armed state (default: "Confirm?")
  - `onConfirm()`: called on second click
  - `armedTimeoutMs`: auto-disarm timeout (default: 3000)

- [ ] **Step 1: Create double-confirm-button.css**

```css
/* web/design-system/double-confirm-button.css */
.rt-dbl-btn {
  height: 28px;
  padding: 0 10px;
  border: 1px solid var(--rt-border-strong);
  border-radius: var(--rt-r-sm);
  background: transparent;
  color: var(--rt-muted);
  font: inherit;
  font-size: 12px;
  font-weight: 500;
  cursor: pointer;
  transition: background 120ms ease, color 120ms ease, border-color 120ms ease;
}

.rt-dbl-btn:hover {
  background: var(--rt-fill-hover);
  color: var(--rt-text);
}

.rt-dbl-btn.is-armed {
  border-color: var(--rt-error);
  background: rgba(245, 101, 101, 0.12);
  color: var(--rt-error);
  font-weight: 600;
}

.rt-dbl-btn.is-armed:hover {
  background: rgba(245, 101, 101, 0.2);
}

.rt-dbl-btn:disabled { opacity: 0.55; cursor: wait; }
```

- [ ] **Step 2: Create double-confirm-button.js**

```js
// web/design-system/double-confirm-button.js
import { escapeHtml } from '../core.js';

const STYLE_ID = 'rt-dbl-btn-styles';
const DEFAULT_TIMEOUT_MS = 3000;

export function mountDoubleConfirmButton(container, config) {
  injectStyles();
  let armed = false;
  let timer = null;

  function render() {
    const label = armed ? (config.confirmLabel || 'Confirm?') : config.label;
    const cls = armed ? 'rt-dbl-btn is-armed' : 'rt-dbl-btn';
    container.innerHTML = `<button type="button" class="${cls}">${escapeHtml(label)}</button>`;
  }

  function onClick(e) {
    const btn = e.target.closest('.rt-dbl-btn');
    if (!btn) return;
    if (!armed) {
      armed = true;
      render();
      timer = setTimeout(disarm, config.armedTimeoutMs || DEFAULT_TIMEOUT_MS);
    } else {
      disarm();
      config.onConfirm?.();
    }
  }

  function disarm() {
    clearTimeout(timer);
    timer = null;
    armed = false;
    render();
  }

  render();
  container.addEventListener('click', onClick);

  return {
    disarm,
    dispose() {
      clearTimeout(timer);
      container.removeEventListener('click', onClick);
      container.innerHTML = '';
    },
  };
}

function injectStyles() {
  if (document.getElementById(STYLE_ID)) return;
  const link = document.createElement('link');
  link.id = STYLE_ID;
  link.rel = 'stylesheet';
  link.href = '/web/design-system/double-confirm-button.css';
  document.head.appendChild(link);
}
```

- [ ] **Step 3: Commit**

```bash
git add web/design-system/double-confirm-button.js web/design-system/double-confirm-button.css
git commit -m "feat(design-system): add DoubleConfirmButton component"
```

---

### Task 4: Design System — DataTable

**Files:**
- Create: `web/design-system/data-table.js`
- Create: `web/design-system/data-table.css`

**Interfaces:**
- Consumes: nothing
- Produces: `mountDataTable(container, { columns, rows, emptyMessage?, onRowClick?, rowClass? }) → { dispose(), update({ rows }) }`
  - `columns`: `Array<{ key, label, width?, render?(value, row) → string, class? }>`
  - `rows`: `Array<object>` — each row object has keys matching column `key`
  - `emptyMessage`: string shown when rows is empty
  - `onRowClick(row, event)`: optional click handler
  - `rowClass(row) → string`: optional fn returning extra CSS classes for a row

- [ ] **Step 1: Create data-table.css**

```css
/* web/design-system/data-table.css */
.rt-data-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;
  font-variant-numeric: tabular-nums;
}

.rt-data-table th,
.rt-data-table td {
  padding: 10px 12px;
  border-bottom: 1px solid var(--rt-border);
  text-align: left;
  vertical-align: middle;
}

.rt-data-table th {
  color: var(--rt-muted);
  font-size: 11px;
  font-weight: 650;
  text-transform: uppercase;
  letter-spacing: 0.04em;
  white-space: nowrap;
}

.rt-data-table td { color: var(--rt-text); }
.rt-data-table tbody tr:hover { background: var(--rt-fill-subtle); }

.rt-data-table .is-paused { opacity: 0.55; }
.rt-data-table .is-done { opacity: 0.55; }

.rt-data-table-empty {
  padding: 34px 14px;
  color: var(--rt-muted);
  text-align: center;
  font-size: 13px;
}
```

- [ ] **Step 2: Create data-table.js**

```js
// web/design-system/data-table.js
import { escapeHtml } from '../core.js';

const STYLE_ID = 'rt-data-table-styles';

export function mountDataTable(container, config) {
  injectStyles();
  let state = { rows: config.rows || [] };

  function render() {
    if (state.rows.length === 0) {
      container.innerHTML = `<div class="rt-data-table-empty">${escapeHtml(config.emptyMessage || 'No data')}</div>`;
      return;
    }
    const cols = config.columns;
    const headCells = cols.map((col) => {
      const style = col.width ? ` style="width:${escapeHtml(col.width)}"` : '';
      const cls = col.class ? ` class="${escapeHtml(col.class)}"` : '';
      return `<th${style}${cls}>${escapeHtml(col.label || '')}</th>`;
    }).join('');

    const bodyRows = state.rows.map((row) => {
      const cls = config.rowClass ? config.rowClass(row) : '';
      const cells = cols.map((col) => {
        const value = row[col.key];
        const cellClass = col.class ? ` class="${escapeHtml(col.class)}"` : '';
        const html = col.render ? col.render(value, row) : escapeHtml(String(value ?? ''));
        return `<td${cellClass}>${html}</td>`;
      }).join('');
      return `<tr${cls ? ` class="${escapeHtml(cls)}"` : ''}>${cells}</tr>`;
    }).join('');

    container.innerHTML = `
      <table class="rt-data-table">
        <thead><tr>${headCells}</tr></thead>
        <tbody>${bodyRows}</tbody>
      </table>
    `;
  }

  function onClick(e) {
    if (!config.onRowClick) return;
    const tr = e.target.closest('tbody tr');
    if (!tr) return;
    const idx = [...container.querySelectorAll('tbody tr')].indexOf(tr);
    if (idx >= 0 && idx < state.rows.length) {
      config.onRowClick(state.rows[idx], e);
    }
  }

  render();
  container.addEventListener('click', onClick);

  return {
    update({ rows }) {
      state = { rows: rows || [] };
      render();
    },
    dispose() {
      container.removeEventListener('click', onClick);
      container.innerHTML = '';
    },
  };
}

function injectStyles() {
  if (document.getElementById(STYLE_ID)) return;
  const link = document.createElement('link');
  link.id = STYLE_ID;
  link.rel = 'stylesheet';
  link.href = '/web/design-system/data-table.css';
  document.head.appendChild(link);
}
```

- [ ] **Step 3: Commit**

```bash
git add web/design-system/data-table.js web/design-system/data-table.css
git commit -m "feat(design-system): add DataTable component"
```

---

### Task 5: Design System — FormSection

**Files:**
- Create: `web/design-system/form-section.js`
- Create: `web/design-system/form-section.css`

**Interfaces:**
- Consumes: nothing
- Produces: `mountFormSection(container, { label, name, type?, placeholder?, value?, help?, required?, disabled? }) → { dispose(), update({ value?, disabled? }), getValue() → string }`
  - `type`: `'text' | 'email' | 'date'` (default: `'text'`)

- [ ] **Step 1: Create form-section.css**

```css
/* web/design-system/form-section.css */
.rt-form-section {
  display: grid;
  gap: 5px;
}

.rt-form-section-label {
  color: var(--rt-muted);
  font-size: 11px;
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 0.04em;
}

.rt-form-section-input {
  width: 100%;
  min-width: 0;
  height: 34px;
  padding: 0 9px;
  border: 1px solid var(--rt-border-strong);
  border-radius: var(--rt-r-sm);
  background: var(--rt-fill-subtle);
  color: var(--rt-text);
  font: inherit;
  font-size: 13px;
}

.rt-form-section-input::placeholder { color: var(--rt-faint); }

.rt-form-section-input:focus {
  outline: none;
  border-color: var(--rt-brand);
  background: var(--rt-fill-hover);
}

.rt-form-section-input:disabled { opacity: 0.55; }

.rt-form-section-help {
  color: var(--rt-faint);
  font-size: 11px;
}
```

- [ ] **Step 2: Create form-section.js**

```js
// web/design-system/form-section.js
import { escapeHtml } from '../core.js';

const STYLE_ID = 'rt-form-section-styles';

export function mountFormSection(container, config) {
  injectStyles();
  let state = { value: config.value || '', disabled: !!config.disabled };

  function render() {
    const helpHtml = config.help
      ? `<span class="rt-form-section-help">${escapeHtml(config.help)}</span>`
      : '';
    const reqAttr = config.required ? ' required' : '';
    const disAttr = state.disabled ? ' disabled' : '';
    container.innerHTML = `
      <div class="rt-form-section">
        <span class="rt-form-section-label">${escapeHtml(config.label)}</span>
        <input
          class="rt-form-section-input"
          type="${escapeHtml(config.type || 'text')}"
          name="${escapeHtml(config.name)}"
          value="${escapeHtml(state.value)}"
          placeholder="${escapeHtml(config.placeholder || '')}"
          ${reqAttr}${disAttr}
        >
        ${helpHtml}
      </div>
    `;
  }

  function onInput(e) {
    if (e.target.name === config.name) {
      state = { ...state, value: e.target.value };
    }
  }

  render();
  container.addEventListener('input', onInput);

  return {
    getValue() { return state.value; },
    update({ value, disabled }) {
      if (value != null) state = { ...state, value };
      if (disabled != null) state = { ...state, disabled };
      render();
    },
    dispose() {
      container.removeEventListener('input', onInput);
      container.innerHTML = '';
    },
  };
}

function injectStyles() {
  if (document.getElementById(STYLE_ID)) return;
  const link = document.createElement('link');
  link.id = STYLE_ID;
  link.rel = 'stylesheet';
  link.href = '/web/design-system/form-section.css';
  document.head.appendChild(link);
}
```

- [ ] **Step 3: Commit**

```bash
git add web/design-system/form-section.js web/design-system/form-section.css
git commit -m "feat(design-system): add FormSection component"
```

---

### Task 6: Watch Components — TriggerSelector

**Files:**
- Create: `web/watches/trigger-selector.js`

**Interfaces:**
- Consumes: `mountToggleSwitch` from `web/design-system/toggle-switch.js`, `mountFormSection` from `web/design-system/form-section.js`
- Produces: `mountTriggerSelector(container, { triggers, emailTo?, slackEnabled?, emailEnabled?, stopWhenTriggered?, disabled?, onChange }) → { dispose(), update(config), getState() → TriggerState }`
  - `TriggerState`: `{ slackEnabled, emailEnabled, emailTo, stopWhenTriggered }`
  - `onChange(state: TriggerState)` called on any change

- [ ] **Step 1: Create trigger-selector.js**

```js
// web/watches/trigger-selector.js
import { mountToggleSwitch } from '../design-system/toggle-switch.js';
import { mountFormSection } from '../design-system/form-section.js';

export function mountTriggerSelector(container, config) {
  let state = {
    slackEnabled: config.slackEnabled ?? true,
    emailEnabled: config.emailEnabled ?? false,
    emailTo: config.emailTo || '',
    stopWhenTriggered: config.stopWhenTriggered ?? true,
  };
  const children = [];

  function render() {
    container.innerHTML = '';
    children.forEach((c) => c.dispose());
    children.length = 0;

    const slackHost = document.createElement('div');
    const emailHost = document.createElement('div');
    const emailFieldHost = document.createElement('div');
    const stopHost = document.createElement('div');
    container.appendChild(slackHost);
    container.appendChild(emailHost);
    container.appendChild(emailFieldHost);
    container.appendChild(stopHost);

    children.push(mountToggleSwitch(slackHost, {
      name: 'slack_notify',
      label: 'Slack',
      help: 'Post when a matching site opens.',
      checked: state.slackEnabled,
      disabled: config.disabled,
      onChange(checked) {
        state = { ...state, slackEnabled: checked };
        config.onChange?.(state);
      },
    }));

    children.push(mountToggleSwitch(emailHost, {
      name: 'email_notify',
      label: 'Email',
      help: 'Send email when a matching site opens.',
      checked: state.emailEnabled,
      disabled: config.disabled,
      onChange(checked) {
        state = { ...state, emailEnabled: checked };
        config.onChange?.(state);
        render();
      },
    }));

    if (state.emailEnabled) {
      const emailInput = mountFormSection(emailFieldHost, {
        label: 'Email address',
        name: 'email_to',
        type: 'email',
        placeholder: 'you@example.com',
        value: state.emailTo,
        disabled: config.disabled,
      });
      emailFieldHost.addEventListener('input', (e) => {
        if (e.target.name === 'email_to') {
          state = { ...state, emailTo: e.target.value };
          config.onChange?.(state);
        }
      });
      children.push(emailInput);
    }

    children.push(mountToggleSwitch(stopHost, {
      name: 'stop_when_triggered',
      label: 'Stop when triggered',
      help: 'Mark done after a successful trigger.',
      checked: state.stopWhenTriggered,
      disabled: config.disabled,
      onChange(checked) {
        state = { ...state, stopWhenTriggered: checked };
        config.onChange?.(state);
      },
    }));
  }

  render();

  return {
    getState() { return { ...state }; },
    update(newConfig) {
      if (newConfig.slackEnabled != null) state.slackEnabled = newConfig.slackEnabled;
      if (newConfig.emailEnabled != null) state.emailEnabled = newConfig.emailEnabled;
      if (newConfig.emailTo != null) state.emailTo = newConfig.emailTo;
      if (newConfig.stopWhenTriggered != null) state.stopWhenTriggered = newConfig.stopWhenTriggered;
      if (newConfig.disabled != null) config.disabled = newConfig.disabled;
      render();
    },
    dispose() {
      children.forEach((c) => c.dispose());
      children.length = 0;
      container.innerHTML = '';
    },
  };
}
```

- [ ] **Step 2: Commit**

```bash
git add web/watches/trigger-selector.js
git commit -m "feat(watches): add TriggerSelector component"
```

---

### Task 7: Watch Components — WatchForm

**Files:**
- Create: `web/watches/watch-form.js`
- Create: `web/watches/watch-form.css`

**Interfaces:**
- Consumes: `mountFormSection` from `web/design-system/form-section.js`, `mountTriggerSelector` from `web/watches/trigger-selector.js`
- Produces: `mountWatchForm(container, { mode, watch?, onSubmit, onCancel? }) → { dispose(), setMode(mode, watch?), setLoading(bool), setError(msg?) }`
  - `mode`: `'create' | 'edit'`
  - `onSubmit(data)` where `data = { poi_id, start_date, end_date, trigger_kinds, trigger_config, stop_when_triggered }`
  - `onCancel()` called in edit mode when user cancels

- [ ] **Step 1: Create watch-form.css**

```css
/* web/watches/watch-form.css */
.rt-watch-form {
  background: var(--rt-surface);
  border: 1px solid var(--rt-border);
  border-radius: var(--rt-r-md);
  padding: 16px;
}

.rt-watch-form-title {
  margin: 0 0 12px;
  font-size: 14px;
  font-weight: 650;
  color: var(--rt-text);
}

.rt-watch-form-fields {
  display: grid;
  grid-template-columns: 1fr 1fr 1fr;
  gap: 12px;
  margin-bottom: 14px;
}

.rt-watch-form-triggers {
  display: grid;
  gap: 10px;
  margin-bottom: 14px;
  padding-top: 12px;
  border-top: 1px solid var(--rt-border);
}

.rt-watch-form-actions {
  display: flex;
  gap: 8px;
  justify-content: flex-end;
  padding-top: 12px;
  border-top: 1px solid var(--rt-border);
}

.rt-watch-form-error {
  color: var(--rt-error);
  font-size: 12px;
  margin-bottom: 8px;
}

@media (max-width: 640px) {
  .rt-watch-form-fields {
    grid-template-columns: 1fr;
  }
}
```

- [ ] **Step 2: Create watch-form.js**

```js
// web/watches/watch-form.js
import { escapeHtml } from '../core.js';
import { mountFormSection } from '../design-system/form-section.js';
import { mountTriggerSelector } from './trigger-selector.js';
import {
  TRIGGER_KIND_SLACK_NOTIFY,
  TRIGGER_KIND_EMAIL_NOTIFY,
  watchHasTrigger,
  watchEmailTo,
  watchStopWhenTriggered,
} from '../availability/watch-editor.js';

const STYLE_ID = 'rt-watch-form-styles';

export function mountWatchForm(container, config) {
  injectStyles();
  let mode = config.mode || 'create';
  let watch = config.watch || null;
  let loading = false;
  let error = null;
  const children = [];

  function render() {
    container.innerHTML = '';
    children.forEach((c) => c.dispose());
    children.length = 0;

    const title = mode === 'edit' ? 'Edit Watch' : 'Create Watch';
    const submitLabel = mode === 'edit' ? 'Save' : 'Create';
    const cancelHtml = mode === 'edit'
      ? `<button type="button" class="cg-btn cg-btn-secondary rt-watch-form-cancel">Cancel</button>`
      : '';
    const errorHtml = error
      ? `<div class="rt-watch-form-error">${escapeHtml(error)}</div>`
      : '';
    const disabledAttr = loading ? ' disabled' : '';

    container.innerHTML = `
      <div class="rt-watch-form">
        <h2 class="rt-watch-form-title">${escapeHtml(title)}</h2>
        ${errorHtml}
        <div class="rt-watch-form-fields">
          <div data-field="poi_id"></div>
          <div data-field="start_date"></div>
          <div data-field="end_date"></div>
        </div>
        <div class="rt-watch-form-triggers" data-field="triggers"></div>
        <div class="rt-watch-form-actions">
          ${cancelHtml}
          <button type="button" class="cg-btn cg-btn-primary rt-watch-form-submit"${disabledAttr}>${escapeHtml(submitLabel)}</button>
        </div>
      </div>
    `;

    const poiHost = container.querySelector('[data-field="poi_id"]');
    const startHost = container.querySelector('[data-field="start_date"]');
    const endHost = container.querySelector('[data-field="end_date"]');
    const triggerHost = container.querySelector('[data-field="triggers"]');

    const poiField = mountFormSection(poiHost, {
      label: 'POI ID',
      name: 'poi_id',
      placeholder: 'e.g. 42',
      value: watch?.poi_id != null ? String(watch.poi_id) : '',
      disabled: loading,
    });
    children.push(poiField);

    const startField = mountFormSection(startHost, {
      label: 'Start date',
      name: 'start_date',
      type: 'date',
      value: watch?.start_date || '',
      disabled: loading,
    });
    children.push(startField);

    const endField = mountFormSection(endHost, {
      label: 'End date',
      name: 'end_date',
      type: 'date',
      value: watch?.end_date || '',
      disabled: loading,
    });
    children.push(endField);

    const triggerSelector = mountTriggerSelector(triggerHost, {
      slackEnabled: watch ? watchHasTrigger(watch, TRIGGER_KIND_SLACK_NOTIFY) : true,
      emailEnabled: watch ? watchHasTrigger(watch, TRIGGER_KIND_EMAIL_NOTIFY) : false,
      emailTo: watch ? watchEmailTo(watch) : '',
      stopWhenTriggered: watchStopWhenTriggered(watch, true),
      disabled: loading,
    });
    children.push(triggerSelector);

    container.querySelector('.rt-watch-form-submit')?.addEventListener('click', () => {
      const triggerState = triggerSelector.getState();
      const triggerKinds = [];
      const triggerConfig = {};
      if (triggerState.slackEnabled) triggerKinds.push(TRIGGER_KIND_SLACK_NOTIFY);
      if (triggerState.emailEnabled) {
        triggerKinds.push(TRIGGER_KIND_EMAIL_NOTIFY);
        const emailTo = triggerState.emailTo.trim();
        if (emailTo) triggerConfig[TRIGGER_KIND_EMAIL_NOTIFY] = { to: emailTo };
      }
      config.onSubmit({
        poi_id: poiField.getValue(),
        start_date: startField.getValue(),
        end_date: endField.getValue(),
        trigger_kinds: triggerKinds,
        trigger_config: triggerConfig,
        stop_when_triggered: triggerState.stopWhenTriggered,
      });
    });

    container.querySelector('.rt-watch-form-cancel')?.addEventListener('click', () => {
      config.onCancel?.();
    });
  }

  render();

  return {
    setMode(newMode, newWatch) {
      mode = newMode;
      watch = newWatch || null;
      error = null;
      render();
    },
    setLoading(val) {
      loading = val;
      render();
    },
    setError(msg) {
      error = msg || null;
      render();
    },
    dispose() {
      children.forEach((c) => c.dispose());
      children.length = 0;
      container.innerHTML = '';
    },
  };
}

function injectStyles() {
  if (document.getElementById(STYLE_ID)) return;
  const link = document.createElement('link');
  link.id = STYLE_ID;
  link.rel = 'stylesheet';
  link.href = '/web/watches/watch-form.css';
  document.head.appendChild(link);
}
```

- [ ] **Step 3: Commit**

```bash
git add web/watches/watch-form.js web/watches/watch-form.css
git commit -m "feat(watches): add WatchForm component"
```

---

### Task 8: Watch Components — WatchTable

**Files:**
- Create: `web/watches/watch-table.js`
- Create: `web/watches/watch-table.css`

**Interfaces:**
- Consumes: `mountDataTable` from `web/design-system/data-table.js`, `mountDoubleConfirmButton` from `web/design-system/double-confirm-button.js`
- Produces: `mountWatchTable(container, { watches, poiNames, onEdit, onPauseResume, onDelete }) → { dispose(), update({ watches, poiNames }) }`

- [ ] **Step 1: Create watch-table.css**

```css
/* web/watches/watch-table.css */
.rt-watch-table-wrap {
  overflow-x: auto;
}

.rt-watch-table-poi {
  color: var(--rt-brand);
  text-decoration: none;
  font-weight: 500;
}
.rt-watch-table-poi:hover { text-decoration: underline; }

.rt-watch-table-trigger {
  display: flex;
  gap: 6px;
  align-items: center;
}
.rt-watch-table-trigger svg { width: 14px; height: 14px; vertical-align: -2px; }

.rt-watch-table-actions {
  display: flex;
  gap: 6px;
  align-items: center;
}

.rt-watch-table-act {
  width: 28px;
  height: 28px;
  display: grid;
  place-items: center;
  background: transparent;
  border: 0;
  border-radius: var(--rt-r-sm);
  color: var(--rt-faint);
  cursor: pointer;
  font-size: 13px;
}
.rt-watch-table-act:hover { background: var(--rt-fill-hover); color: var(--rt-text); }
.rt-watch-table-act:disabled { opacity: 0.55; cursor: wait; }

.rt-watch-table-status {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 12px;
  font-weight: 500;
}
.rt-watch-table-status-active { color: var(--rt-success); }
.rt-watch-table-status-paused { color: var(--rt-warn); }
.rt-watch-table-status-done { color: var(--rt-muted); }
```

- [ ] **Step 2: Create watch-table.js**

```js
// web/watches/watch-table.js
import { escapeHtml } from '../core.js';
import { mountDataTable } from '../design-system/data-table.js';
import { mountDoubleConfirmButton } from '../design-system/double-confirm-button.js';

const STYLE_ID = 'rt-watch-table-styles';

const SLACK_ICON =
  '<svg viewBox="0 0 122.8 122.8" role="img" aria-label="Slack"><title>Slack</title>' +
  '<path fill="#E01E5A" d="M25.8 77.6c0 7.1-5.8 12.9-12.9 12.9S0 84.7 0 77.6s5.8-12.9 12.9-12.9h12.9v12.9zm6.5 0c0-7.1 5.8-12.9 12.9-12.9s12.9 5.8 12.9 12.9v32.3c0 7.1-5.8 12.9-12.9 12.9s-12.9-5.8-12.9-12.9V77.6z"/>' +
  '<path fill="#36C5F0" d="M45.2 25.8c-7.1 0-12.9-5.8-12.9-12.9S38.1 0 45.2 0s12.9 5.8 12.9 12.9v12.9H45.2zm0 6.5c7.1 0 12.9 5.8 12.9 12.9s-5.8 12.9-12.9 12.9H12.9C5.8 58.1 0 52.3 0 45.2s5.8-12.9 12.9-12.9h32.3z"/>' +
  '<path fill="#2EB67D" d="M97 45.2c0-7.1 5.8-12.9 12.9-12.9s12.9 5.8 12.9 12.9-5.8 12.9-12.9 12.9H97V45.2zm-6.5 0c0 7.1-5.8 12.9-12.9 12.9s-12.9-5.8-12.9-12.9V12.9C64.7 5.8 70.5 0 77.6 0s12.9 5.8 12.9 12.9v32.3z"/>' +
  '<path fill="#ECB22E" d="M77.6 97c7.1 0 12.9 5.8 12.9 12.9s-5.8 12.9-12.9 12.9-12.9-5.8-12.9-12.9V97h12.9zm0-6.5c-7.1 0-12.9-5.8-12.9-12.9s5.8-12.9 12.9-12.9h32.3c7.1 0 12.9 5.8 12.9 12.9s-5.8 12.9-12.9 12.9H77.6z"/>' +
  '</svg>';
const EMAIL_ICON =
  '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" role="img" aria-label="Email"><title>Email</title>' +
  '<rect width="20" height="16" x="2" y="4" rx="2"/>' +
  '<path d="m22 7-8.97 5.7a1.94 1.94 0 0 1-2.06 0L2 7"/>' +
  '</svg>';

export function mountWatchTable(container, config) {
  injectStyles();
  let state = { watches: config.watches || [], poiNames: config.poiNames || new Map() };
  let tableCtrl = null;
  const deleteButtons = [];

  function columns() {
    return [
      { key: 'poi', label: 'POI', render: (_, row) => poiCellHtml(row, state.poiNames) },
      { key: 'date', label: 'Date', render: (_, row) => fmtDate(row.start_date) },
      { key: 'trigger', label: 'Trigger', render: (_, row) => triggerCellHtml(row) },
      { key: 'status', label: 'Status', render: (_, row) => statusCellHtml(row) },
      { key: 'last_checked', label: 'Last checked', render: (_, row) => checkedCellHtml(row) },
      { key: 'actions', label: '', width: '140px', render: (_, row) => actionsCellHtml(row) },
    ];
  }

  function render() {
    container.innerHTML = '<div class="rt-watch-table-wrap" data-table-host></div>';
    const host = container.querySelector('[data-table-host]');
    tableCtrl?.dispose();
    tableCtrl = mountDataTable(host, {
      columns: columns(),
      rows: state.watches,
      emptyMessage: 'No watches yet',
      rowClass: (row) => row.status === 'paused' ? 'is-paused' : row.status === 'done' ? 'is-done' : '',
    });
    mountDeleteButtons();
  }

  function mountDeleteButtons() {
    deleteButtons.forEach((d) => d.dispose());
    deleteButtons.length = 0;
    container.querySelectorAll('[data-delete-host]').forEach((host) => {
      const id = host.dataset.watchId;
      const ctrl = mountDoubleConfirmButton(host, {
        label: '🗑',
        confirmLabel: 'Delete?',
        onConfirm: () => config.onDelete?.(id),
      });
      deleteButtons.push(ctrl);
    });
  }

  function onClick(e) {
    const editBtn = e.target.closest('[data-act="edit"]');
    if (editBtn) {
      config.onEdit?.(editBtn.dataset.id);
      return;
    }
    const pauseBtn = e.target.closest('[data-act="pause"]');
    if (pauseBtn) {
      config.onPauseResume?.(pauseBtn.dataset.id, 'paused');
      return;
    }
    const resumeBtn = e.target.closest('[data-act="resume"]');
    if (resumeBtn) {
      config.onPauseResume?.(resumeBtn.dataset.id, 'active');
      return;
    }
  }

  render();
  container.addEventListener('click', onClick);

  return {
    update({ watches, poiNames }) {
      if (watches) state.watches = watches;
      if (poiNames) state.poiNames = poiNames;
      render();
    },
    dispose() {
      tableCtrl?.dispose();
      deleteButtons.forEach((d) => d.dispose());
      container.removeEventListener('click', onClick);
      container.innerHTML = '';
    },
  };
}

function poiCellHtml(watch, poiNames) {
  const id = watch.poi_id;
  if (id == null) return escapeHtml(watchFallbackName(watch));
  const name = poiNames.get(id) || `POI ${id}`;
  return `<a class="rt-watch-table-poi" href="/?poi=${encodeURIComponent(id)}">${escapeHtml(name)}</a>`;
}

function watchFallbackName(watch) {
  const r = watch.campsite;
  if (r?.name) return r.loop ? `${r.loop} / ${r.name}` : r.name;
  return `Watch #${watch.id}`;
}

function triggerCellHtml(watch) {
  const kinds = Array.isArray(watch.trigger_kinds) ? watch.trigger_kinds : [];
  if (kinds.length === 0) return '<span style="color:var(--rt-faint)">—</span>';
  const parts = [];
  if (kinds.includes('slack_notify')) parts.push(SLACK_ICON);
  if (kinds.includes('email_notify')) parts.push(EMAIL_ICON);
  if (kinds.includes('atc')) parts.push('🛒');
  return `<span class="rt-watch-table-trigger">${parts.join(' ')}</span>`;
}

function statusCellHtml(watch) {
  const s = watch.status || 'active';
  const labels = { active: 'Active', paused: 'Paused', done: 'Done' };
  return `<span class="rt-watch-table-status rt-watch-table-status-${escapeHtml(s)}">${labels[s] || s}</span>`;
}

function checkedCellHtml(watch) {
  if (watch.last_run_status === 'failed') {
    const err = watch.last_run_error ? ` title="${escapeHtml(watch.last_run_error)}"` : '';
    return `<span style="color:var(--rt-warn)"${err}>⚠ error</span>`;
  }
  const at = watch.last_run_at;
  if (!at) return '<span style="color:var(--rt-faint)">—</span>';
  return `<span title="${escapeHtml(at)}">${escapeHtml(relativeTime(at))}</span>`;
}

function actionsCellHtml(watch) {
  if (watch.status === 'done') {
    const glyph = doneKind(watch) === 'expired' ? '⌛' : '✅';
    return `<span class="rt-watch-table-actions"><span title="${glyph === '⌛' ? 'Expired' : 'Found'}">${glyph}</span></span>`;
  }
  const toggleBtn = watch.status === 'paused'
    ? `<button type="button" class="rt-watch-table-act" data-act="resume" data-id="${watch.id}" title="Resume" aria-label="Resume">▶</button>`
    : `<button type="button" class="rt-watch-table-act" data-act="pause" data-id="${watch.id}" title="Pause" aria-label="Pause">⏸</button>`;
  const editBtn = `<button type="button" class="rt-watch-table-act" data-act="edit" data-id="${watch.id}" title="Edit" aria-label="Edit">✏️</button>`;
  const deleteHost = `<span data-delete-host data-watch-id="${watch.id}"></span>`;
  return `<span class="rt-watch-table-actions">${toggleBtn}${editBtn}${deleteHost}</span>`;
}

function fmtDate(iso) {
  if (!iso) return '<span style="color:var(--rt-faint)">—</span>';
  const d = new Date(`${iso}T00:00:00Z`);
  if (Number.isNaN(d.getTime())) return escapeHtml(iso);
  return escapeHtml(d.toLocaleDateString('en-US', { month: 'short', day: 'numeric', timeZone: 'UTC' }));
}

function relativeTime(iso) {
  const then = new Date(iso).getTime();
  if (Number.isNaN(then)) return iso;
  const secs = Math.max(0, Math.round((Date.now() - then) / 1000));
  if (secs < 60) return 'just now';
  const mins = Math.round(secs / 60);
  if (mins < 60) return `${mins}m ago`;
  const hrs = Math.round(mins / 60);
  if (hrs < 24) return `${hrs}h ago`;
  const days = Math.round(hrs / 24);
  return `${days}d ago`;
}

function doneKind(w) {
  const end = w.end_date ?? '';
  return end && end < new Date().toISOString().slice(0, 10) ? 'expired' : 'found';
}

function injectStyles() {
  if (document.getElementById(STYLE_ID)) return;
  const link = document.createElement('link');
  link.id = STYLE_ID;
  link.rel = 'stylesheet';
  link.href = '/web/watches/watch-table.css';
  document.head.appendChild(link);
}
```

- [ ] **Step 3: Commit**

```bash
git add web/watches/watch-table.js web/watches/watch-table.css
git commit -m "feat(watches): add WatchTable component"
```

---

### Task 9: Page Controller — watches-page.js

**Files:**
- Create: `web/watches/watches-page.js`

**Interfaces:**
- Consumes: `mountBanner`, `mountWatchForm`, `mountWatchTable`, watches API (`listWatches`, `getWatch`, `createWatch`, `updateWatch`, `deleteWatch`), `notifyWatchesChanged`, `fetchPoiDetail`
- Produces: entry point module, no exports (self-initializing on import)

- [ ] **Step 1: Create watches-page.js**

```js
// web/watches/watches-page.js
import { listWatches, getWatch, createWatch, updateWatch, deleteWatch } from '../api/watches-api.js';
import { fetchPoiDetail } from '../api/poi-api.js';
import { notifyWatchesChanged } from '../availability/watch-events.js';
import { mountBanner } from '../design-system/banner.js';
import { mountWatchForm } from './watch-form.js';
import { mountWatchTable } from './watch-table.js';

const WATCH_LIST_LIMIT = 200;

let bannerCtrl = null;
let formCtrl = null;
let tableCtrl = null;
const poiNameCache = new Map();

async function init() {
  const bannerHost = document.getElementById('banner-host');
  const formHost = document.getElementById('form-host');
  const tableHost = document.getElementById('table-host');

  formCtrl = mountWatchForm(formHost, {
    mode: 'create',
    onSubmit: handleSubmit,
    onCancel: handleCancel,
  });

  tableCtrl = mountWatchTable(tableHost, {
    watches: [],
    poiNames: poiNameCache,
    onEdit: handleEdit,
    onPauseResume: handlePauseResume,
    onDelete: handleDelete,
  });

  await loadWatches();
  applyUrlAction(bannerHost);
}

async function loadWatches() {
  const [active, paused, done] = await Promise.all([
    listWatches({ status: 'active', limit: WATCH_LIST_LIMIT }),
    listWatches({ status: 'paused', limit: WATCH_LIST_LIMIT }),
    listWatches({ status: 'done', limit: WATCH_LIST_LIMIT }),
  ]);
  const watches = [
    ...(active?.watches || []),
    ...(paused?.watches || []),
    ...(done?.watches || []),
  ].sort(byStartDate);
  await ensurePoiNames(watches);
  tableCtrl.update({ watches, poiNames: poiNameCache });
}

async function ensurePoiNames(list) {
  const ids = [...new Set(list.map((w) => w.poi_id).filter((id) => id != null && !poiNameCache.has(id)))];
  await Promise.all(ids.map(async (id) => {
    try {
      const d = await fetchPoiDetail(id);
      poiNameCache.set(id, d?.properties?.name || d?.name || `POI ${id}`);
    } catch {
      poiNameCache.set(id, `POI ${id}`);
    }
  }));
}

async function handleSubmit(data) {
  formCtrl.setLoading(true);
  formCtrl.setError(null);
  const bannerHost = document.getElementById('banner-host');
  try {
    if (formCtrl._editingId) {
      await updateWatch(formCtrl._editingId, data);
      showBanner(bannerHost, 'success', 'Watch updated.');
    } else {
      await createWatch(data);
      showBanner(bannerHost, 'success', 'Watch created.');
    }
    formCtrl.setMode('create', null);
    formCtrl._editingId = null;
    notifyWatchesChanged();
    await loadWatches();
  } catch (err) {
    formCtrl.setError(err?.message || 'Could not save. Try again.');
  } finally {
    formCtrl.setLoading(false);
  }
}

function handleCancel() {
  formCtrl.setMode('create', null);
  formCtrl._editingId = null;
}

async function handleEdit(id) {
  try {
    const detail = await getWatch(id);
    const watch = detail.watch || detail;
    formCtrl._editingId = id;
    formCtrl.setMode('edit', watch);
    document.getElementById('form-host')?.scrollIntoView({ behavior: 'smooth', block: 'start' });
  } catch {
    const bannerHost = document.getElementById('banner-host');
    showBanner(bannerHost, 'error', 'Could not load watch for editing.');
  }
}

async function handlePauseResume(id, newStatus) {
  try {
    await updateWatch(id, { status: newStatus });
    notifyWatchesChanged();
    await loadWatches();
  } catch {
    const bannerHost = document.getElementById('banner-host');
    showBanner(bannerHost, 'error', 'Could not update watch status.');
  }
}

async function handleDelete(id) {
  const bannerHost = document.getElementById('banner-host');
  try {
    await deleteWatch(id);
    notifyWatchesChanged();
    showBanner(bannerHost, 'success', 'Watch deleted.');
    if (String(formCtrl._editingId) === String(id)) {
      formCtrl.setMode('create', null);
      formCtrl._editingId = null;
    }
    await loadWatches();
  } catch {
    showBanner(bannerHost, 'error', 'Could not delete watch.');
  }
}

async function applyUrlAction(bannerHost) {
  const params = new URLSearchParams(window.location.search);
  const action = params.get('action');
  if (!action) return;

  const id = params.get('id');
  const poiId = params.get('poi_id');
  const startDate = params.get('start_date');

  clearUrlParams();

  if (action === 'create') {
    const prefill = {};
    if (poiId) prefill.poi_id = poiId;
    if (startDate) prefill.start_date = startDate;
    formCtrl.setMode('create', prefill);
  } else if (action === 'modify' && id) {
    await handleEdit(id);
  } else if (action === 'delete' && id) {
    try {
      await deleteWatch(id);
      notifyWatchesChanged();
      showBanner(bannerHost, 'success', 'Watch deleted.');
      await loadWatches();
    } catch {
      showBanner(bannerHost, 'error', 'Could not delete watch.');
    }
  }
}

function clearUrlParams() {
  const url = new URL(window.location.href);
  url.search = '';
  window.history.replaceState(null, '', `${url.pathname}${url.hash}`);
}

function showBanner(host, type, message) {
  bannerCtrl?.dispose();
  bannerCtrl = mountBanner(host, { type, message, dismissable: true, onDismiss: () => { bannerCtrl = null; } });
}

function byStartDate(a, b) {
  const da = a.start_date ?? '';
  const db = b.start_date ?? '';
  if (da === db) return 0;
  if (!da) return 1;
  if (!db) return -1;
  return da < db ? -1 : 1;
}

init();
```

- [ ] **Step 2: Commit**

```bash
git add web/watches/watches-page.js
git commit -m "feat(watches): add page controller with URL param handling"
```

---

### Task 10: HTML Shell — watches.html

**Files:**
- Create: `watches.html`

**Interfaces:**
- Consumes: `web/watches/watches-page.js` (entry point)
- Produces: the `/watches` route served by the backend

- [ ] **Step 1: Create watches.html**

```html
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="theme-color" content="#26272d">
<title>Watches</title>
<link rel="stylesheet" href="/web/design-system/tokens.css">
<style>
  * { box-sizing: border-box; }
  html, body {
    margin: 0;
    min-height: 100%;
    background: var(--rt-bg-sunken);
    color: var(--rt-text);
    font-family: var(--rt-sans);
  }
  body { padding: 20px; }
  a { color: var(--rt-brand); text-decoration: none; }
  a:hover { color: var(--rt-brand-hover); text-decoration: underline; }
  .shell { max-width: 1080px; margin: 0 auto; }
  .top {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 16px;
    margin-bottom: 16px;
  }
  h1 { margin: 0; font-size: 22px; line-height: 1.2; font-weight: 650; }
  .sub { margin-top: 4px; color: var(--rt-muted); font-size: 13px; }
  .nav {
    display: flex;
    align-items: center;
    gap: 10px;
  }
  .nav a {
    border: 1px solid var(--rt-border-strong);
    background: var(--rt-fill-subtle);
    color: var(--rt-text);
    border-radius: var(--rt-r-md);
    height: 34px;
    padding: 0 12px;
    font-size: 13px;
    display: inline-flex;
    align-items: center;
  }
  .nav a:hover { background: var(--rt-fill-hover); text-decoration: none; }
  .nav a.outside-link { gap: 7px; }
  .nav a.outside-link::after {
    content: "";
    width: 10px; height: 10px;
    border-top: 1.5px solid currentColor;
    border-right: 1.5px solid currentColor;
    opacity: 0.7;
  }
  #banner-host { margin-bottom: 12px; }
  #banner-host:empty { margin-bottom: 0; }
  #form-host { margin-bottom: 16px; }
  .cg-btn {
    height: 34px;
    padding: 0 14px;
    border: 1px solid var(--rt-border-strong);
    border-radius: var(--rt-r-md);
    font: inherit;
    font-size: 13px;
    font-weight: 500;
    cursor: pointer;
    display: inline-flex;
    align-items: center;
    justify-content: center;
  }
  .cg-btn-primary {
    border-color: var(--rt-brand);
    background: var(--rt-brand);
    color: #fff;
    font-weight: 600;
  }
  .cg-btn-primary:hover { background: var(--rt-brand-hover); border-color: var(--rt-brand-hover); }
  .cg-btn-secondary {
    background: var(--rt-fill-subtle);
    color: var(--rt-text);
  }
  .cg-btn-secondary:hover { background: var(--rt-fill-hover); }
  .cg-btn:disabled { opacity: 0.55; cursor: default; }
  @media (max-width: 640px) {
    body { padding: 12px; }
    .top { align-items: flex-start; flex-direction: column; }
  }
</style>
</head>
<body>
<main class="shell">
  <header class="top">
    <div>
      <h1>Watches</h1>
      <div class="sub">Manage availability watches — get notified when campsites open up.</div>
    </div>
    <nav class="nav">
      <a class="outside-link" href="/">Map</a>
      <a class="outside-link" href="/availability.html">Dashboard</a>
    </nav>
  </header>

  <div id="banner-host"></div>
  <div id="form-host"></div>
  <div id="table-host"></div>
</main>
<script type="module" src="/web/watches/watches-page.js"></script>
</body>
</html>
```

- [ ] **Step 2: Verify in browser**

Start the dev server (`tilt up` or equivalent) and navigate to `/watches.html`. Confirm:
- Page loads with the create form and empty table
- Create a watch via the form → banner shows "Watch created", table updates
- Click Edit → form switches to edit mode with pre-filled values
- Click Pause/Resume → row status toggles immediately
- Click Delete → first click arms (shows "Delete?"), second click deletes, banner shows "Watch deleted"
- Test URL params: `/watches.html?action=create&poi_id=42&start_date=2026-08-01` pre-fills form
- Test URL params: `/watches.html?action=delete&id=1` deletes and shows banner

- [ ] **Step 3: Commit**

```bash
git add watches.html
git commit -m "feat: add /watches page HTML shell"
```

---

### Task 11: Backend Route — Serve watches.html

**Files:**
- Modify: the backend's static file serving or routing configuration to serve `watches.html` at `/watches`

**Interfaces:**
- Consumes: `watches.html` file at project root
- Produces: GET `/watches` returns the HTML page (same pattern as `/availability.html` is served)

- [ ] **Step 1: Check how availability.html is served**

Look at the Ktor routing configuration to understand how `availability.html` is mapped. The same pattern applies to `watches.html`. The file likely needs to be added as a static resource or a route that serves the file.

- [ ] **Step 2: Add the route**

Follow the exact same pattern as `availability.html`. If it's served as a static file from the project root, simply adding `watches.html` to the root may be sufficient. If there's explicit routing, add:

```kotlin
get("/watches") {
    call.respondFile(File("watches.html"))
}
```

(Exact code depends on how `availability.html` is served — check the backend routing.)

- [ ] **Step 3: Verify `/watches` loads in the browser**

Navigate to `http://localhost:<port>/watches` and confirm the page renders.

- [ ] **Step 4: Commit**

```bash
git commit -m "feat: serve /watches route from backend"
```
