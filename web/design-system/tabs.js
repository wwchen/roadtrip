import { tabsTemplate } from './tabs-template.js';

const STYLE_ID = 'rt-tabs-styles';

/**
 * Mount a tab rail into `container`.
 *
 * @param {Element} container - The host element that will receive the tabs markup.
 * @param {{
 *   tabs: Array<{ id: string, label: string }>,
 *   active?: string,
 *   onChange?: (id: string) => void,
 * }} config
 * @returns {{ getActive(): string, setActive(id: string): void, dispose(): void }}
 */
export function mountTabs(container, config = {}) {
  const {
    tabs = [],
    onChange,
  } = config;

  let active = config.active || (tabs[0] ? tabs[0].id : '');

  injectStyles();

  function render() {
    container.innerHTML = tabsTemplate({ tabs, active });
  }

  function onClick(e) {
    const btn = e.target && typeof e.target.closest === 'function'
      ? e.target.closest('[data-tab]')
      : null;
    if (!btn) return;
    const id = btn.dataset ? btn.dataset.tab : btn.getAttribute('data-tab');
    if (!id || id === active) return;
    active = id;
    render();
    if (typeof onChange === 'function') onChange(id);
  }

  render();
  container.addEventListener('click', onClick);

  return {
    /** Returns the currently active tab id. */
    getActive() {
      return active;
    },

    /**
     * Programmatically select a tab by id. Calls onChange if provided.
     * @param {string} id
     */
    setActive(id) {
      if (id === active) return;
      active = id;
      render();
      if (typeof onChange === 'function') onChange(id);
    },

    /** Remove event listeners and clear the DOM. */
    dispose() {
      container.removeEventListener('click', onClick);
      container.innerHTML = '';
    },
  };
}

function injectStyles() {
  if (typeof document === 'undefined') return;
  if (document.getElementById(STYLE_ID)) return;
  const link = document.createElement('link');
  link.id = STYLE_ID;
  link.rel = 'stylesheet';
  link.href = '/web/design-system/tabs.css';
  document.head.appendChild(link);
}
