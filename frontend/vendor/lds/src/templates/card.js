import { attrs, cx } from './attrs.js';
import { slot } from './escape.js';

export function card({
  kicker, title, body, meta, actions, emphasis, hue,
  selectable, selected, disabled, onClick,
  className = '', children, ...rest
} = {}) {
  const cls = cx('lds-card', selectable && 'lds-card--selectable',
    emphasis && `emph-${emphasis}`, hue && `hue-${hue}`, className);
  const content = (kicker ? `<div class="lds-card__kicker">${slot(kicker)}</div>` : '')
    + (title ? `<div class="lds-card__title">${slot(title)}</div>` : '')
    + (body ? `<p class="lds-card__body">${slot(body)}</p>` : '')
    + slot(children)
    + (meta ? `<div class="lds-card__divider lds-card__divider--meta"></div>` : '')
    + (meta ? `<div class="lds-card__meta">${slot(meta)}</div>` : '')
    + (actions ? `<div class="lds-card__actions">${slot(actions)}</div>` : '');
  // A selectable card is a real button, not a div with a click handler: that is
  // what gives it Space/Enter, a focus ring and a reported pressed state for
  // free. aria-pressed rather than aria-checked — the card is a toggle, and a
  // group of them is not necessarily exclusive.
  void onClick;
  if (selectable) {
    return `<button${attrs({
      type: 'button', className: cls,
      'aria-pressed': selected ? 'true' : 'false',
      'aria-disabled': disabled ? 'true' : undefined,
      disabled, ...rest,
    })}>${content}</button>`;
  }
  return `<div${attrs({ className: cls, ...rest })}>${content}</div>`;
}
