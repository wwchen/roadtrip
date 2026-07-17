import { doubleConfirmButtonTemplate } from './double-confirm-button-template.js';

const STYLE_ID = 'rt-dbl-btn-styles';
const DEFAULT_TIMEOUT_MS = 3000;

export function mountDoubleConfirmButton(container, config) {
  injectStyles();
  let armed = false;
  let timer = null;

  function render() {
    container.innerHTML = doubleConfirmButtonTemplate({
      label: config.label,
      armed,
      confirmLabel: config.confirmLabel,
    });
  }

  function onClick(e) {
    const btn = e.target.closest('.rt-dbl-btn');
    if (!btn) return;
    if (!armed) {
      armed = true;
      render();
      timer = setTimeout(disarm, config.armedTimeoutMs || DEFAULT_TIMEOUT_MS);
    } else {
      disarm();
      config.onConfirm?.();
    }
  }

  function disarm() {
    clearTimeout(timer);
    timer = null;
    armed = false;
    render();
  }

  render();
  container.addEventListener('click', onClick);

  return {
    disarm,
    dispose() {
      clearTimeout(timer);
      container.removeEventListener('click', onClick);
      container.innerHTML = '';
    },
  };
}

function injectStyles() {
  if (document.getElementById(STYLE_ID)) return;
  const link = document.createElement('link');
  link.id = STYLE_ID;
  link.rel = 'stylesheet';
  link.href = '/web/design-system/double-confirm-button.css';
  document.head.appendChild(link);
}
