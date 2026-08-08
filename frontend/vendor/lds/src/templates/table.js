import { attrs, cx } from './attrs.js';
import { slot } from './escape.js';

export function table({ columns = [], rows = [], className = '' } = {}) {
  const head = columns.map((c) => `<th>${slot(c.label)}</th>`).join('');
  const body = rows.map((r) => `<tr>${columns.map((c) => `<td>${slot(r[c.key])}</td>`).join('')}</tr>`).join('');
  return `<table${attrs({ className: cx('lds-table', className) })}>`
    + `<thead><tr>${head}</tr></thead><tbody>${body}</tbody></table>`;
}
