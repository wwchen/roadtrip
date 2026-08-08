import { attrs, cx } from './attrs.js';
import { slot } from './escape.js';
import { spriteSvg } from './sprite.js';

export function link({ children, href, variant, iconEnd, iconHref, className = '', ...rest } = {}) {
  const cls = cx('lds-link',
    variant === 'quiet' && 'lds-link--quiet',
    variant === 'standalone' && 'lds-link--standalone', className);
  // a standalone link gets a trailing chevron by default — it is the affordance
  // that replaces the underline it drops. `iconEnd: null` suppresses it.
  const mark = variant === 'standalone' && iconEnd !== null
    ? (typeof iconEnd === 'string' || iconEnd === undefined
      ? spriteSvg(iconEnd || 'chevron-right', iconHref)
      : slot(iconEnd))
    : '';
  return `<a${attrs({ className: cls, href, ...rest })}>${slot(children)}${mark}</a>`;
}
