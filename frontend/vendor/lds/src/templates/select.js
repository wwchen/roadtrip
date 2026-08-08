import { attrs, cx } from './attrs.js';
import { slot } from './escape.js';

export function select({
  label, id, help, error, required, options = [], value, defaultValue, className = '', ...rest
} = {}) {
  const cls = cx('lds-field', error && 'lds-field--error', className);
  // A <select> has no `value` attribute — the selected OPTION carries `selected`.
  // Passing it through would emit an attribute the browser ignores and preselect
  // nothing, which looks like a working control right up until someone submits.
  const current = value !== undefined ? value : defaultValue;
  const option = (o) => {
    const opt = typeof o === 'string' ? { value: o, label: o } : o;
    return `<option${attrs({
      selected: current !== undefined && opt.value === current,
      value: opt.value,
    })}>${slot(opt.label)}</option>`;
  };
  const body = options.map((o) => (o && o.options)
    ? `<optgroup${attrs({ label: o.label })}>${o.options.map(option).join('')}</optgroup>`
    : option(o)).join('');
  return `<div${attrs({ className: cls, 'data-status': error ? 'error' : undefined })}>`
    + (label ? `<label${attrs({ htmlFor: id, className: required ? 'lds-field__req' : '' })}>${slot(label)}</label>` : '')
    + `<select${attrs({ id, ...rest }, 'select')}>${body}</select>`
    + (error ? `<span class="lds-field__error">${slot(error)}</span>`
      : help ? `<span class="lds-field__help">${slot(help)}</span>` : '')
    + `</div>`;
}
