import { attrs, cx } from './attrs.js';
import { slot } from './escape.js';
import { spriteSvg } from './sprite.js';

// Segmented control — a value picker, not navigation.
//
// Tabs change what you are looking at; this changes a property of what you are
// already looking at. They look alike, so the distinction lives here rather than
// in the author's head: this renders a radiogroup, which gives arrow-key
// movement and one tab stop for the whole group for free. A row of buttons would
// need all of that written by hand.
//
// `name` is required rather than generated: the radios only behave as one group
// if they share it, and a generated name would differ between a server render
// and the client's.
export function segmentedControl({
  options = [], value, name, size, full, iconsOnly, label, iconHref, className = '', ...rest
} = {}) {
  const cls = cx('lds-seg', size && `lds-seg--${size}`, full && 'lds-seg--full',
    iconsOnly && 'lds-seg--icons', className);
  const body = options.map((o) => {
    const opt = typeof o === 'string' ? { value: o, label: o } : o;
    return `<label${attrs({ className: 'lds-seg__option', title: iconsOnly ? opt.label : undefined })}>`
      + `<input${attrs({
        type: 'radio', name, value: opt.value,
        checked: value === opt.value, disabled: opt.disabled,
        'aria-label': iconsOnly ? opt.label : undefined,
      }, 'input')}/>`
      + (opt.icon ? spriteSvg(opt.icon, iconHref) : '')
      + (iconsOnly ? '' : `<span>${slot(opt.label)}</span>`)
      + `</label>`;
  }).join('');
  return `<div${attrs({ className: cls, role: 'radiogroup', 'aria-label': label, ...rest })}>${body}</div>`;
}
