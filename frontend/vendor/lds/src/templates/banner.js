import { resolveSprite } from '../icon-sprite.js';
import { STATUS_ICON } from '../status-icons.js';
import { escapeHtml, slot } from './escape.js';

// Banner, as an HTML string.
//
// The framework-free binding of the same component. It reads the same status
// map and the same sprite resolver as the React one and emits byte-identical
// markup — `npm test` renders both across a prop matrix and fails on any
// difference, so this cannot drift into being a second, subtly different
// banner.
//
// Slots (`title`, `children`, `actions`) take TEXT by default and escape it.
// Pass `raw(html)` to compose markup into one — the unsafe path has to be
// chosen rather than reached by forgetting to escape.
export function banner({
  status, emphasis, page, title, icon, iconHref, children, actions,
  dismissible, onDismiss, className = '', ...rest
} = {}) {
  const spriteHref = resolveSprite(iconHref);
  const cls = ['lds-banner',
    status ? `lds-banner--${status}` : '',
    page ? 'lds-banner--page' : '',
    emphasis ? `emph-${emphasis}` : '',
    className].filter(Boolean).join(' ');

  const sprite = (name) => `<svg class="lds-icon" aria-hidden="true"><use href="${escapeHtml(`${spriteHref}#${name}`)}"></use></svg>`;

  const statusIcon = icon !== undefined
    ? slot(icon)
    : (STATUS_ICON[status] ? sprite(STATUS_ICON[status]) : '');

  // Mirrors the React tree exactly, including the inner <div> that wraps the
  // label composite even when it is empty.
  const body = [
    title ? `<div class="lds-banner__title">${slot(title)}</div>` : '',
    slot(children),
    actions ? `<div class="lds-banner__actions">${slot(actions)}</div>` : '',
  ].join('');

  const dismiss = dismissible
    ? `<button type="button" class="lds-banner__dismiss" aria-label="Dismiss">${sprite('close')}</button>`
    : '';

  // `onDismiss` is accepted and ignored: the controller binds the handler by
  // delegation after mounting. Taking the same prop name as React keeps one
  // vocabulary across the two bindings rather than two.
  void onDismiss;

  const attrs = Object.entries(rest)
    .filter(([, v]) => v !== undefined && v !== null && v !== false)
    .map(([k, v]) => ` ${k}="${escapeHtml(v)}"`).join('');

  return `<div class="${escapeHtml(cls)}"${status ? ` data-status="${escapeHtml(status)}"` : ''}${attrs}>`
    + `${statusIcon}<div>${body}</div>${dismiss}</div>`;
}
