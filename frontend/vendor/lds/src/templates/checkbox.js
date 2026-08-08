import { attrs, cx } from './attrs.js';
import { slot } from './escape.js';

export function checkbox({ label, id, className = '', ...rest } = {}) {
  return `<label${attrs({ className: cx('lds-check', className) })}>`
    + `<input${attrs({ type: 'checkbox', id, ...rest }, 'input')}/>`
    + `<span class="lds-check__box"></span>${slot(label)}</label>`;
}
