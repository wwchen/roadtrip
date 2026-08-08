// Serialising a prop bag to HTML attributes, the way React does it.
//
// The templates pass through whatever extra props they are given, exactly as the
// React components spread `...rest` onto their root element. To emit the same
// bytes, this has to reproduce React's rules rather than a reasonable
// approximation of them:
//
//   - camelCase prop names map to their HTML attribute (readOnly → readonly).
//   - `true` renders as an empty string, `false`/`null`/`undefined` disappear.
//   - handlers and other functions never reach the DOM.
//   - `style` objects hyphenate and take a `px` unit where the property needs one.
//   - on form controls, `checked` and `value` are emitted LAST regardless of
//     where they sat in the prop bag — React pulls them out and appends them.
//
// That last one is not a detail anyone would guess. It is why the frozen
// contract exists: `<input type="checkbox" disabled="" readonly="" checked=""/>`
// comes from props written `{checked, disabled, readOnly}`.
import { escapeHtml } from './escape.js';

// Props whose HTML attribute name is not simply the lowercased prop name.
const ATTR_NAMES = {
  className: 'class',
  htmlFor: 'for',
  // In HTML the `checked` and `value` ATTRIBUTES are the defaults — the live
  // state lives on the property. So React's uncontrolled-input props are not an
  // approximation here, they are the exact same thing spelled differently, and
  // mapping them means markup ported off React keeps working.
  defaultChecked: 'checked',
  defaultValue: 'value',
  readOnly: 'readonly',
  tabIndex: 'tabindex',
  maxLength: 'maxlength',
  minLength: 'minlength',
  autoComplete: 'autocomplete',
  autoFocus: 'autofocus',
  spellCheck: 'spellcheck',
  crossOrigin: 'crossorigin',
  dateTime: 'datetime',
  formAction: 'formaction',
  noValidate: 'novalidate',
  encType: 'enctype',
  acceptCharset: 'accept-charset',
  httpEquiv: 'http-equiv',
  srcSet: 'srcset',
  inputMode: 'inputmode',
  enterKeyHint: 'enterkeyhint',
  contentEditable: 'contenteditable',
  colSpan: 'colspan',
  rowSpan: 'rowspan',
  useMap: 'usemap',
  referrerPolicy: 'referrerpolicy',
};

// CSS properties that take a bare number. Everything else gets `px` when the
// value is numeric — React's rule, and the reason `size: 32` becomes `32px`.
const UNITLESS = new Set([
  'animationIterationCount', 'aspectRatio', 'borderImageOutset', 'borderImageSlice',
  'borderImageWidth', 'boxFlex', 'boxFlexGroup', 'boxOrdinalGroup', 'columnCount',
  'columns', 'flex', 'flexGrow', 'flexPositive', 'flexShrink', 'flexNegative',
  'flexOrder', 'gridArea', 'gridRow', 'gridRowEnd', 'gridRowSpan', 'gridRowStart',
  'gridColumn', 'gridColumnEnd', 'gridColumnSpan', 'gridColumnStart', 'fontWeight',
  'lineClamp', 'lineHeight', 'opacity', 'order', 'orphans', 'tabSize', 'widows',
  'zIndex', 'zoom', 'fillOpacity', 'floodOpacity', 'stopOpacity', 'strokeDasharray',
  'strokeDashoffset', 'strokeMiterlimit', 'strokeOpacity', 'strokeWidth',
]);

const hyphenate = (prop) => prop
  .replace(/([A-Z])/g, '-$1').toLowerCase()
  .replace(/^ms-/, '-ms-');

/** Serialises a style object the way React does, or passes a string through. */
export function styleAttr(style) {
  if (style === null || style === undefined || style === false) return '';
  if (typeof style === 'string') return style;
  return Object.entries(style)
    .filter(([, v]) => v !== null && v !== undefined && v !== '')
    .map(([k, v]) => {
      const value = typeof v === 'number' && v !== 0 && !UNITLESS.has(k) ? `${v}px` : v;
      return `${hyphenate(k)}:${value}`;
    })
    .join(';');
}

const attrName = (key) => ATTR_NAMES[key]
  || (key.startsWith('data-') || key.startsWith('aria-') ? key : key.toLowerCase());

/** One attribute, or '' when React would have dropped it. */
function one(key, value) {
  if (value === null || value === undefined || value === false) return '';
  if (typeof value === 'function') return '';
  // React drops handler props entirely; they are bound by the controller after
  // mounting, not serialised into markup.
  if (/^on[A-Z]/.test(key)) return '';
  if (key === 'style') {
    const css = styleAttr(value);
    return css ? ` style="${escapeHtml(css)}"` : '';
  }
  if (value === true) return ` ${attrName(key)}=""`;
  return ` ${attrName(key)}="${escapeHtml(value)}"`;
}

// React appends these after every other attribute on a form control, `checked`
// first — verified against the serialiser, not assumed.
const DEFERRED = ['checked', 'defaultChecked', 'value', 'defaultValue'];

/**
 * Serialises a prop bag to an attribute string, in prop order.
 *
 * `tag` matters only for form controls, where `value` and `checked` are moved to
 * the end to match React.
 */
export function attrs(props, tag) {
  if (!props) return '';
  const defer = tag === 'input' || tag === 'select' || tag === 'textarea';
  let out = '';
  for (const [key, value] of Object.entries(props)) {
    if (defer && DEFERRED.includes(key)) continue;
    out += one(key, value);
  }
  if (defer) {
    for (const key of DEFERRED) {
      if (key in props) out += one(key, props[key]);
    }
  }
  return out;
}

/** Joins class names, dropping the falsy ones. */
export const cx = (...parts) => parts.filter(Boolean).join(' ');
