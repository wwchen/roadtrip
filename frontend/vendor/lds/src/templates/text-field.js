import { attrs, cx } from './attrs.js';
import { slot } from './escape.js';
import { spriteSvg } from './sprite.js';

export function textField({
  label, id, help, error, required,
  iconStart, iconEnd, endAction, prefix, iconHref,
  className = '', ...rest
} = {}) {
  const cls = cx('lds-field',
    error && 'lds-field--error',
    iconStart && 'lds-field--has-start',
    (iconEnd || endAction) && 'lds-field--has-end',
    className);
  const sprite = (icon) => typeof icon === 'string' ? spriteSvg(icon, iconHref) : slot(icon);
  const input = `<input${attrs({ id, type: 'text', ...rest }, 'input')}/>`;
  // prefix is a whole control (a dial-code select) joined into one box; icons are
  // inset over the input's own padding so the field stays a single target.
  const control = prefix
    ? `<div class="lds-field__group"><div class="lds-field__dial">${slot(prefix)}</div>`
      + `<div class="lds-field__number">${input}</div></div>`
    : `<div class="lds-field__wrap">`
      + (iconStart ? `<span class="lds-field__adorn lds-field__adorn--start">${sprite(iconStart)}</span>` : '')
      + input
      + (endAction
        ? `<button${attrs({
          type: 'button',
          className: 'lds-field__adorn lds-field__adorn--end lds-field__adorn--action',
          'aria-label': endAction.label,
        })}>${sprite(endAction.icon)}</button>`
        : iconEnd ? `<span class="lds-field__adorn lds-field__adorn--end">${sprite(iconEnd)}</span>` : '')
      + `</div>`;
  return `<div${attrs({ className: cls, 'data-status': error ? 'error' : undefined })}>`
    + (label ? `<label${attrs({ htmlFor: id, className: required ? 'lds-field__req' : '' })}>${slot(label)}</label>` : '')
    + control
    + (error ? `<span class="lds-field__error">${slot(error)}</span>`
      : help ? `<span class="lds-field__help">${slot(help)}</span>` : '')
    + `</div>`;
}
