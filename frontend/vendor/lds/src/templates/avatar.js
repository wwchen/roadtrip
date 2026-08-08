import { attrs, cx } from './attrs.js';
import { slot } from './escape.js';
import { spriteSvg } from './sprite.js';
import { hueForName, initialsForName } from '../avatar-name.js';

export function avatar({ name, src, alt, size, hue, ring, iconHref, className = '', ...rest } = {}) {
  const initials = initialsForName(name);
  const cls = cx('lds-avatar', size && `lds-avatar--${size}`,
    `hue-${hue || hueForName(name || 'anon')}`, ring && 'lds-avatar--ring', className);
  const label = alt || name || 'Person';
  // no name to draw from — a person icon, never a random letter or an empty disc
  const inner = src ? `<img${attrs({ src, alt: label })}/>`
    : initials || spriteSvg('person', iconHref);
  return `<span${attrs({ className: cls, role: 'img', 'aria-label': label, title: name || undefined, ...rest })}>`
    + `${src || !initials ? inner : slot(initials)}</span>`;
}
