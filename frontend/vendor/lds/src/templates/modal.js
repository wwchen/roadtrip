import { attrs, cx } from './attrs.js';
import { slot } from './escape.js';
import { spriteSvg } from './sprite.js';

export function modal({
  title, children, actions, cancel, onClose, onBack,
  size, sheet, side, largeTitle = true, iconHref, className = '',
} = {}) {
  const cls = cx('lds-modal', size && `lds-modal--${size}`,
    sheet && 'lds-modal--sheet', side && 'lds-modal--side', className);
  // The footer is a button group, not a bespoke flex row. With a cancel present
  // it splits: cancel to the opposite end, confirm last in source and so at the
  // end of the reading direction — and on a phone the group stacks, confirm on
  // top. Cancel is never a peer sitting next to confirm.
  const groupCls = ['lds-btn-group', 'lds-btn-group--stack',
    cancel ? 'lds-btn-group--split' : 'lds-btn-group--end'].join(' ');
  const footer = (cancel || actions)
    ? `<div class="lds-modal__actions"><div class="${groupCls}" role="group">`
      + `${slot(cancel)}${slot(actions)}</div></div>`
    : '';
  // back pops ONE level of a stacked flow; close dismisses the whole stack.
  void onClose; void onBack;
  return `<div class="lds-modal-scrim"><div${attrs({ className: cls })}>`
    + ((sheet || side) ? `<div class="lds-modal__handle"></div>` : '')
    + `<div class="lds-modal__header">`
    + (onBack ? `<button type="button" class="lds-modal__back" aria-label="Back">${spriteSvg('chevron-left', iconHref)}</button>` : '')
    + (title ? `<div class="lds-modal__title">${slot(title)}</div>` : '')
    + `<button type="button" class="lds-modal__close" aria-label="Close">${spriteSvg('close', iconHref)}</button>`
    + `</div><div class="lds-modal__body">`
    // The large title sits at the TOP OF THE SCROLL REGION, directly above the
    // body copy, so title and content read as one block. It leaves on its own as
    // the body scrolls; the bar title fades in behind it.
    + ((largeTitle && title) ? `<h2 class="lds-modal__title lds-modal__title--large">${slot(title)}</h2>` : '')
    + `${slot(children)}</div>${footer}</div></div>`;
}
