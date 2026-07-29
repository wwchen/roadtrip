import { escapeHtml } from '../core.js';

/**
 * Pure function — no DOM access. Returns an HTML string for the modal overlay.
 *
 * @param {{ title?: string, sheetOnMobile?: boolean }} config
 * @returns {string}
 */
export function modalTemplate({ title = '', sheetOnMobile = false } = {}) {
  const cardClass = sheetOnMobile ? 'rt-modal-card rt-modal-sheet' : 'rt-modal-card';
  // data-modal-drag marks where a sheet drag may start. The handle and the
  // header only — never the body, which scrolls, and where a drag would fight
  // the scroller.
  const grabHandle = sheetOnMobile
    ? '<div class="rt-modal-grab-handle" data-modal-drag aria-hidden="true"></div>'
    : '';
  const titleHtml = title
    ? `<span class="rt-modal-title">${escapeHtml(title)}</span>`
    : '<span class="rt-modal-title"></span>';

  return `
    <div class="rt-modal-scrim" data-modal-backdrop></div>
    <div class="${escapeHtml(cardClass)}" role="dialog" aria-modal="true"${title ? ` aria-label="${escapeHtml(title)}"` : ''}>
      ${grabHandle}
      <div class="rt-modal-header" data-modal-drag>
        ${titleHtml}
        <button type="button" class="rt-modal-close-btn" data-modal-close aria-label="Close">&times;</button>
      </div>
      <div class="rt-modal-body" data-modal-body></div>
    </div>
  `;
}
