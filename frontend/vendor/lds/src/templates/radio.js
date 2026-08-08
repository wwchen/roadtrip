import { attrs, cx } from './attrs.js';
import { slot } from './escape.js';

export function radio({ label, id, name, className = '', ...rest } = {}) {
  return `<label${attrs({ className: cx('lds-check', 'lds-check--radio', className) })}>`
    + `<input${attrs({ type: 'radio', id, name, ...rest }, 'input')}/>`
    + `<span class="lds-check__box"></span>${slot(label)}</label>`;
}
