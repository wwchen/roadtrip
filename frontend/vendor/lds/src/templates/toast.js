import { attrs, cx } from './attrs.js';
import { slot } from './escape.js';
import { spriteSvg } from './sprite.js';
import { STATUS_ICON } from '../status-icons.js';

// Toast — a transient message the system raises over the layout.
//
// The line against Banner is not visual, it is about ownership: a banner is a
// field the page contains and it stays until the condition it describes changes;
// a toast is chrome the system raises and it leaves on its own. So a toast is the
// wrong place for anything the user must act on or re-read — if dismissing it can
// lose information, it wanted to be a banner.
export function toast({
  status, title, children, actions,
  dismissible = true, onDismiss, dismissLabel = 'Dismiss',
  icon, iconHref, className = '', ...rest
} = {}) {
  const mark = icon !== undefined
    ? slot(icon)
    : (STATUS_ICON[status] ? spriteSvg(STATUS_ICON[status], iconHref) : '');
  // An error is assertive because it interrupts a task; everything else is
  // polite and waits for a gap, rather than cutting across what is being read.
  void onDismiss;
  return `<div${attrs({
    className: cx('lds-toast', className),
    'data-status': status || undefined,
    role: status === 'error' ? 'alert' : 'status',
    'aria-live': status === 'error' ? 'assertive' : 'polite',
    ...rest,
  })}>${mark}<div class="lds-toast__content">`
    + (title ? `<div class="lds-toast__title">${slot(title)}</div>` : '')
    + slot(children)
    + (actions ? `<div class="lds-toast__actions">${slot(actions)}</div>` : '')
    + `</div>`
    + (dismissible
      ? `<button${attrs({ type: 'button', className: 'lds-toast__dismiss', 'aria-label': dismissLabel })}>`
        + `${spriteSvg('close', iconHref)}</button>`
      : '')
    + `</div>`;
}
