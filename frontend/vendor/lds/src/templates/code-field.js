import { attrs, cx } from './attrs.js';
import { slot } from './escape.js';
import { spriteSvg } from './sprite.js';

// One-time code (2FA). One box per digit, because the boxes are what tell the
// user how many to expect.
//
// This is the FIRST PAINT only. Paste-fill, backspace-steps-back and focus
// movement live in the controller — see ../controllers/code-field.js — because
// they are behaviour, and behaviour cannot be a string.
export function codeField({
  label, help, error, success, verifying, length = 6, groupAfter, size,
  value = '', iconHref, className = '', ...rest
} = {}) {
  const cls = cx('lds-field', error && 'lds-field--error', success && 'lds-field--success', className);
  const code = value || '';
  let cells = '';
  for (let i = 0; i < length; i++) {
    if (groupAfter && i === groupAfter) cells += `<span class="lds-field__code-gap"></span>`;
    cells += `<input${attrs({
      type: 'text', inputMode: 'numeric', autoComplete: i === 0 ? 'one-time-code' : 'off',
      maxLength: length, 'aria-label': `Digit ${i + 1}`,
      // A verified or in-flight code is read-only rather than disabled: disabled
      // would drop the digits out of the tab order and stop a screen reader
      // announcing what was actually entered.
      readOnly: !!(success || verifying),
      value: code[i] || '', ...rest,
    }, 'input')}/>`;
  }
  // role=status/alert, so the verdict is announced when it arrives rather than
  // only being visible. This is the one field the user cannot self-check.
  const note = error
    ? `<span class="lds-field__error" role="alert">${spriteSvg('close-circle-fill', iconHref)}${slot(error)}</span>`
    : success
      ? `<span class="lds-field__success" role="status">${spriteSvg('check-circle', iconHref)}`
        + `${slot(success === true ? 'Verified' : success)}</span>`
      : verifying
        ? `<span class="lds-field__help" role="status">${slot(verifying === true ? 'Checking…' : verifying)}</span>`
        : help ? `<span class="lds-field__help">${slot(help)}</span>` : '';
  return `<div${attrs({ className: cls, 'data-status': error ? 'error' : undefined })}>`
    + (label ? `<label>${slot(label)}</label>` : '')
    + `<div class="lds-field__code${size === 'sm' ? ' lds-field__code--sm' : ''}">${cells}</div>`
    + `${note}</div>`;
}
