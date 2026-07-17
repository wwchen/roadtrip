import { escapeHtml } from '../core.js';

export function watchFormTemplate({ mode, error, loading }) {
  const title = mode === 'edit' ? 'Edit Watch' : 'Create Watch';
  const submitLabel = mode === 'edit' ? 'Save' : 'Create';
  const cancelHtml = mode === 'edit'
    ? '<button type="button" class="cg-btn cg-btn-secondary rt-watch-form-cancel">Cancel</button>'
    : '';
  const errorHtml = error
    ? `<div class="rt-watch-form-error">${escapeHtml(error)}</div>`
    : '';
  const disabledAttr = loading ? ' disabled' : '';

  return `
    <div class="rt-watch-form">
      <h2 class="rt-watch-form-title">${escapeHtml(title)}</h2>
      ${errorHtml}
      <div class="rt-watch-form-fields">
        <div data-field="poi_id"></div>
        <div data-field="start_date"></div>
        <div data-field="end_date"></div>
      </div>
      <div class="rt-watch-form-triggers" data-field="triggers"></div>
      <div class="rt-watch-form-actions">
        ${cancelHtml}
        <button type="button" class="cg-btn cg-btn-primary rt-watch-form-submit"${disabledAttr}>${escapeHtml(submitLabel)}</button>
      </div>
    </div>
  `;
}
