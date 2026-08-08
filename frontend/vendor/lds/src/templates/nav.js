import { attrs, cx } from './attrs.js';
import { slot } from './escape.js';
import { spriteSvg } from './sprite.js';

// Nav — two bars that share a name because they sit in the same slot, not
// because they do the same job.
//
//   variant="brand"  a marketing header: logo, links, one level deep.
//   variant="bar"    the chrome of a screen inside a stack.
//
// The bar carries `onBack`, which pops ONE level of a flow, and it is
// deliberately the same affordance as Modal's — same 36px round target, same
// chevron-left, same origin edge — because a pushed screen and a bottom sheet
// are one navigation seen from two angles.
export function nav({
  variant = 'brand', logo, links,
  title, subtitle, onBack, backLabel = 'Back', actions,
  sticky, scrolled, iconHref, className = '', children, ...rest
} = {}) {
  const bar = variant === 'bar';
  const cls = cx('lds-nav', bar && 'lds-nav--bar', sticky && 'lds-nav--sticky',
    bar && scrolled && 'lds-nav--scrolled', className);

  if (!bar) {
    return `<nav${attrs({ className: cls, ...rest })}>`
      + (logo ? `<div class="lds-nav__logo">${slot(logo)}</div>` : '')
      + (links ? `<div class="lds-nav__links lds-nav__spacer">${slot(links)}</div>` : '')
      + `${slot(children)}</nav>`;
  }

  // aria-label rather than a heading: the bar labels the region, and promoting
  // its title to an <h1> would fight whatever heading the screen itself has.
  return `<nav${attrs({
    className: cls,
    'aria-label': typeof title === 'string' ? title : undefined,
    ...rest,
  })}>`
    + (onBack ? `<button${attrs({ type: 'button', className: 'lds-nav__back', 'aria-label': backLabel })}>`
      + `${spriteSvg('chevron-left', iconHref)}</button>` : '')
    + ((title || subtitle) ? `<div class="lds-nav__titles">`
      + (title ? `<div class="lds-nav__title">${slot(title)}</div>` : '')
      + (subtitle ? `<div class="lds-nav__subtitle">${slot(subtitle)}</div>` : '')
      + `</div>` : '')
    + (actions ? `<div class="lds-nav__actions">${slot(actions)}</div>` : '')
    + `${slot(children)}</nav>`;
}
