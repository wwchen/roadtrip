import { attrs, cx } from './attrs.js';
import { slot } from './escape.js';
import { spriteSvg } from './sprite.js';
import { STATUS_ICON } from '../status-icons.js';

// Status carries a fixed meaning icon from Open Icons — the same map Banner and
// Toast use, so an inline error and a banner error never disagree about what red
// means. See ../status-icons.js.
export function inline({ status, icon, iconHref, children, className = '', ...rest } = {}) {
  const cls = cx('lds-inline', status && `lds-inline--${status}`, className);
  const mark = icon !== undefined
    ? slot(icon)
    : (STATUS_ICON[status] ? spriteSvg(STATUS_ICON[status], iconHref) : '');
  return `<span${attrs({ className: cls, 'data-status': status || undefined, ...rest })}>`
    + (mark ? `<span class="lds-inline__icon">${mark}</span>` : '')
    + slot(children) + `</span>`;
}
