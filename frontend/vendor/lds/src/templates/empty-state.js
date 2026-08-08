import { attrs, cx } from './attrs.js';
import { slot } from './escape.js';
import { spriteSvg } from './sprite.js';

export function emptyState({
  icon, image, imageAlt = '', expressive, iconHref, title, body, actions, className = '',
} = {}) {
  const cls = cx('lds-empty', expressive && 'lds-empty--expressive', className);
  // icon takes a sprite name or composed markup; image takes a src or markup.
  // Either one is the branding slot — an empty state has room for expression, so
  // it is the one place the system invites a real asset over a utility glyph.
  const mark = typeof icon === 'string' ? spriteSvg(icon, iconHref) : slot(icon);
  const media = typeof image === 'string'
    ? `<img${attrs({ className: 'lds-empty__media', src: image, alt: imageAlt })}/>`
    : slot(image);
  return `<div${attrs({ className: cls })}>${media || mark}`
    + (title ? `<div class="lds-empty__title">${slot(title)}</div>` : '')
    + (body ? `<p class="lds-empty__body">${slot(body)}</p>` : '')
    + (actions ? `<div class="lds-empty__actions">${slot(actions)}</div>` : '')
    + `</div>`;
}
