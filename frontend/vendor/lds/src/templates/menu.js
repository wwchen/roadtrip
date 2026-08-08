import { attrs, cx } from './attrs.js';
import { slot } from './escape.js';

export function menu({ items = [], className = '' } = {}) {
  const body = items.map((it) => it.separator
    ? `<hr class="lds-menu__separator"/>`
    : `<button${attrs({
      type: 'button',
      className: 'lds-menu__item' + (it.danger ? ' lds-menu__item--danger' : ''),
      disabled: it.disabled,
    })}>${slot(it.icon)}<span class="lds-menu__label">${slot(it.label)}</span>`
      + (it.hint ? `<span class="lds-menu__hint">${slot(it.hint)}</span>` : '')
      + `</button>`).join('');
  return `<div${attrs({ className: cx('lds-menu', className) })}>${body}</div>`;
}
