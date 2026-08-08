import { attrs, cx } from './attrs.js';
import { slot } from './escape.js';
import { spriteSvg } from './sprite.js';

export function button({
  variant = 'primary', size, iconOnly, fab, extended, emphasis, hue, armed,
  iconStart, iconEnd, subtitle, iconHref,
  href, disabled, className = '', children, ...rest
} = {}) {
  const stacked = subtitle !== undefined;
  const cls = cx('lds-btn',
    variant && `lds-btn--${variant}`,
    size === 'sm' ? 'lds-btn--sm' : size === 'lg' ? 'lds-btn--lg' : '',
    (iconOnly || (fab && !extended)) && 'lds-btn--icon',
    fab && 'lds-btn--fab',
    fab && extended && 'lds-btn--extended',
    stacked && 'lds-btn--stacked',
    emphasis && `emph-${emphasis}`,
    hue && `hue-${hue}`,
    armed && 'is-armed',
    className);
  // an icon slot takes a sprite name or composed markup.
  const mark = (icon) => icon === undefined || icon === null ? ''
    : `<span class="lds-btn__icon">${typeof icon === 'string' ? spriteSvg(icon, iconHref) : slot(icon)}</span>`;
  const label = children === undefined ? '' : `<span class="lds-btn__label">${slot(children)}</span>`;
  const body = mark(iconStart)
    + (stacked
      ? `<span class="lds-btn__text">${label}<span class="lds-btn__subtitle">${slot(subtitle)}</span></span>`
      : label)
    + mark(iconEnd);
  // A link that must look like a button IS this button, rendered as an anchor —
  // same sizes, same paint. aria-disabled rather than disabled, which an <a>
  // does not support, and the href is dropped so it is not merely styled dead.
  if (href !== undefined) {
    return `<a${attrs({
      className: cls, href: disabled ? undefined : href,
      role: 'button', 'aria-disabled': disabled ? 'true' : undefined,
      'data-armed': armed ? '' : undefined, ...rest,
    })}>${body}</a>`;
  }
  return `<button${attrs({ className: cls, disabled, 'data-armed': armed ? '' : undefined, ...rest })}>${body}</button>`;
}
