import { attrs, cx } from './attrs.js';
import { slot } from './escape.js';

export function tag({
  children, hue, status, emphasis, size, interactive, inactive, icon, dot,
  className = '', ...rest
} = {}) {
  const cls = cx('lds-tag',
    hue && `hue-${hue}`,
    size === 'sm' && 'lds-tag--sm',
    status && `lds-tag--${status}`,
    emphasis && `emph-${emphasis}`,
    interactive && 'lds-tag--interactive',
    inactive && 'lds-tag--inactive',
    className);
  return `<span${attrs({ className: cls, 'data-status': status || undefined, ...rest })}>`
    + (icon ? `<span class="lds-tag__icon">${slot(icon)}</span>` : '')
    + (dot ? `<span class="lds-tag__dot"></span>` : '')
    + slot(children) + `</span>`;
}
