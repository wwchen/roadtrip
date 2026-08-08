import { attrs, cx } from './attrs.js';
import { escapeHtml, slot } from './escape.js';
import { spriteSvg } from './sprite.js';

export function chip({
  children, selected, size, icon, caret, onRemove, removeLabel = 'Remove',
  iconHref, onClick, className = '', ...rest
} = {}) {
  const cls = cx('lds-chip',
    size === 'sm' ? 'lds-chip--sm' : size === 'lg' ? 'lds-chip--lg' : '',
    selected && 'lds-chip--selected', className);
  const content = (icon ? `<span class="lds-chip__icon">${slot(icon)}</span>` : '')
    + slot(children)
    + (caret ? `<span class="lds-chip__caret">${slot(caret)}</span>` : '')
    + (onRemove
      ? `<button type="button" class="lds-chip__remove" aria-label="${escapeHtml(removeLabel)}">`
        + `${spriteSvg('close', iconHref)}</button>`
      : '');
  // a removable chip is a static tag (e.g. an input's autofill token), not a
  // toggle: the remove button can't nest inside another <button>, so it renders
  // as a span. Pass onClick if the label itself is still actionable.
  if (onRemove) {
    return `<span${attrs({
      className: cls, role: onClick ? 'button' : undefined, tabIndex: onClick ? 0 : undefined,
      'aria-pressed': selected !== undefined ? String(!!selected) : undefined, ...rest,
    })}>${content}</span>`;
  }
  return `<button${attrs({ type: 'button', className: cls, 'aria-pressed': String(!!selected), ...rest })}>${content}</button>`;
}
