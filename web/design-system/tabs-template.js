import { escapeHtml } from '../core.js';

/**
 * Pure function — no DOM access. Returns an HTML string for a tab rail.
 *
 * @param {{
 *   tabs: Array<{ id: string, label: string }>,
 *   active: string,
 * }} config
 * @returns {string}
 */
export function tabsTemplate({ tabs = [], active = '' } = {}) {
  const buttons = tabs.map(({ id, label }) => {
    const isActive = id === active;
    const activeClass = isActive ? ' rt-tabs-tab--active' : '';
    const ariaSelected = isActive ? 'true' : 'false';
    return `<button
        type="button"
        class="rt-tabs-tab${activeClass}"
        data-tab="${escapeHtml(id)}"
        aria-selected="${ariaSelected}"
        role="tab"
      >${escapeHtml(label)}</button>`;
  }).join('\n');

  return `<div class="rt-tabs-rail" role="tablist">\n${buttons}\n</div>`;
}
