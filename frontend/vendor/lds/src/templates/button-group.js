import { attrs, cx } from './attrs.js';
import { slot } from './escape.js';

// The sanctioned action row. Three decisions, one place: direction, hug vs fill,
// and what happens on a phone. Order is meaningful — put the confirming action
// LAST; align="split" sends the first child (cancel / exit) to the opposite end.
export function buttonGroup({
  children, detail, detailNote,
  orientation = 'horizontal', width = 'hug', align = 'end',
  stackOnMobile = true, className = '', ...rest
} = {}) {
  const conversion = detail !== undefined || detailNote !== undefined;
  const cls = cx('lds-btn-group',
    orientation === 'vertical' && 'lds-btn-group--vertical',
    width === 'fill' && 'lds-btn-group--fill',
    conversion ? 'lds-btn-group--conversion' : `lds-btn-group--${align}`,
    !conversion && stackOnMobile && orientation !== 'vertical' && 'lds-btn-group--stack',
    className);
  const bar = conversion
    ? `<div class="lds-btn-group__detail">`
      + (detail ? `<span class="lds-btn-group__detail-title">${slot(detail)}</span>` : '')
      + (detailNote ? `<span class="lds-btn-group__detail-note">${slot(detailNote)}</span>` : '')
      + `</div>`
    : '';
  return `<div${attrs({ className: cls, role: 'group', ...rest })}>${bar}${slot(children)}</div>`;
}
