import { attrs, cx } from './attrs.js';
import { slot } from './escape.js';
import { spriteSvg } from './sprite.js';

export function tabs({ tabs: items = [], active, className = '' } = {}) {
  const body = items.map((t) => t.section
    ? `<div class="lds-tabs__section">${slot(t.section)}</div>`
    : `<button type="button" class="lds-tabs__tab${t.id === active ? ' lds-tabs__tab--active' : ''}">`
      + (t.icon ? spriteSvg(t.icon, t.iconHref) : '')
      + `<span>${slot(t.label)}</span></button>`).join('');
  return `<div${attrs({ className: cx('lds-tabs', className) })}>${body}</div>`;
}
