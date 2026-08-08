import { attrs } from './attrs.js';
import { escapeHtml } from './escape.js';
import { resolveSprite } from '../icon-sprite.js';

export function icon({ name, size, className = '', href, style, ...rest } = {}) {
  const spriteHref = resolveSprite(href);
  const s = size ? { width: size, height: size, ...style } : style;
  return `<svg${attrs({ className: `lds-icon ${className}`, style: s, ...rest })}>`
    + `<use href="${escapeHtml(`${spriteHref}#${name}`)}"></use></svg>`;
}
