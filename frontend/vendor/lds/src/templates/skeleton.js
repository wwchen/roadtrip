import { attrs, cx } from './attrs.js';

export function skeleton({ variant = 'text', last, className = '', style, ...rest } = {}) {
  const cls = cx('lds-skeleton', `lds-skeleton--${variant}`, last && 'lds-skeleton--last', className);
  return `<span${attrs({ className: cls, style, ...rest })}></span>`;
}
