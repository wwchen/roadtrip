import { attrs, cx } from './attrs.js';
import { slot } from './escape.js';

// Tooltip — a LABEL for a control, not a container for prose.
//
// If the text is a sentence the user needs, it belongs in help text where it is
// always visible. A tooltip is what an icon-only button says when you ask it its
// name, which is why it is capped at a short measure and why it must not hold
// anything interactive: a hover bubble cannot be reached by a pointer without a
// hover bridge, so a link inside one is unreachable for some users. That case
// wants a Menu or a Modal.
//
// The bubble ships closed. Opening it on hover AND on focus is the controller's
// job — hover alone means a keyboard user never gets the label, and for an
// icon-only button the tooltip IS the label.
export function tooltip({ label, placement = 'top', children, id, className = '', ...rest } = {}) {
  return `<span${attrs({ className: cx('lds-tooltip', className), ...rest })}>`
    + slot(children)
    // aria-describedby is wired by the controller once the trigger exists: the
    // tooltip ADDS to whatever accessible name the trigger already has rather
    // than replacing it.
    + `<span${attrs({
      id, className: 'lds-tooltip__bubble', role: 'tooltip',
      'data-placement': placement, 'data-open': 'false',
    })}>${slot(label)}</span></span>`;
}
