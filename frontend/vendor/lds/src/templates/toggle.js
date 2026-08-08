import { attrs, cx } from './attrs.js';
import { slot } from './escape.js';

export function toggle({ label, help, id, className = '', ...rest } = {}) {
  const text = (label || help)
    ? `<div class="lds-toggle__text">`
      + (label ? `<span class="lds-toggle__label">${slot(label)}</span>` : '')
      + (help ? `<span class="lds-toggle__help">${slot(help)}</span>` : '')
      + `</div>`
    : '';
  return `<div${attrs({ className: cx('lds-toggle', className) })}>${text}`
    + `<label class="lds-toggle__switch">`
    + `<input${attrs({ type: 'checkbox', id, ...rest }, 'input')}/>`
    + `<span class="lds-toggle__track"></span></label></div>`;
}
