import { bannerTemplate } from './banner-template.js';

const STYLE_ID = 'rt-banner-styles';

export function mountBanner(container, config) {
  injectStyles();
  let state = { type: config.type, message: config.message };

  function render() {
    container.innerHTML = bannerTemplate({
      type: state.type,
      message: state.message,
      dismissable: config.dismissable,
    });
  }

  function onClick(e) {
    if (e.target.closest('.rt-banner-dismiss')) {
      container.innerHTML = '';
      config.onDismiss?.();
    }
  }

  render();
  container.addEventListener('click', onClick);

  return {
    update({ type, message }) {
      state = { type, message };
      render();
    },
    dispose() {
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
  link.href = '/web/design-system/banner.css';
  document.head.appendChild(link);
}
