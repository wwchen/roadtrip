import { toggleSwitchTemplate } from './toggle-switch-template.js';

const STYLE_ID = 'rt-toggle-switch-styles';

export function mountToggleSwitch(container, config) {
  injectStyles();
  let state = { checked: !!config.checked, disabled: !!config.disabled };

  function render() {
    container.innerHTML = toggleSwitchTemplate({
      name: config.name,
      label: config.label,
      help: config.help,
      checked: state.checked,
      disabled: state.disabled,
    });
  }

  function onChange(e) {
    if (e.target.name !== config.name) return;
    state = { ...state, checked: e.target.checked };
    config.onChange?.(state.checked);
  }

  render();
  container.addEventListener('change', onChange);

  return {
    update({ checked, disabled }) {
      state = { checked: !!checked, disabled: disabled != null ? !!disabled : state.disabled };
      render();
    },
    dispose() {
      container.removeEventListener('change', onChange);
      container.innerHTML = '';
    },
  };
}

function injectStyles() {
  if (document.getElementById(STYLE_ID)) return;
  const link = document.createElement('link');
  link.id = STYLE_ID;
  link.rel = 'stylesheet';
  link.href = '/web/design-system/toggle-switch.css';
  document.head.appendChild(link);
}
