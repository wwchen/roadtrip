import { attrs, cx } from './attrs.js';
import { slot } from './escape.js';
import { spriteSvg } from './sprite.js';

export function row({
  lead, title, subtitle, trail, chevron, selected, iconHref,
  compact, roomy, className = '', ...rest
} = {}) {
  const cls = cx('lds-row', compact && 'lds-row--compact', roomy && 'lds-row--roomy',
    (rest.href || rest.onClick) && 'lds-row--interactive', className);
  const tag = rest.href ? 'a' : 'div';
  // chevron={true} draws Open Icons' chevron-right; markup is used as given.
  const mark = chevron === true ? spriteSvg('chevron-right', iconHref) : slot(chevron);
  return `<${tag}${attrs({
    className: cls,
    'aria-selected': selected !== undefined ? String(!!selected) : undefined,
    ...rest,
  })}>`
    + (lead ? `<div class="lds-row__lead">${slot(lead)}</div>` : '')
    + `<div class="lds-row__content"><div class="lds-row__title">${slot(title)}</div>`
    + (subtitle ? `<div class="lds-row__subtitle">${slot(subtitle)}</div>` : '')
    + `</div>`
    + (trail ? `<div class="lds-row__trail">${slot(trail)}</div>` : '')
    + (selected ? `<div class="lds-row__check">${spriteSvg('check', iconHref)}</div>` : '')
    + (mark ? `<div class="lds-row__chevron">${mark}</div>` : '')
    + `</${tag}>`;
}
