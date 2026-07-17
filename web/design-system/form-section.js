import { formSectionTemplate } from './form-section-template.js';

const STYLE_ID = 'rt-form-section-styles';

export function mountFormSection(container, config) {
  injectStyles();
  let state = { value: config.value || '', disabled: !!config.disabled };

  function render() {
    container.innerHTML = formSectionTemplate({
      label: config.label,
      name: config.name,
      type: config.type,
      placeholder: config.placeholder,
      value: state.value,
      help: config.help,
      required: config.required,
      disabled: state.disabled,
    });
  }

  function onInput(e) {
    if (e.target.name === config.name) {
      state = { ...state, value: e.target.value };
    }
  }

  render();
  container.addEventListener('input', onInput);

  return {
    getValue() { return state.value; },
    update({ value, disabled }) {
      if (value != null) state = { ...state, value };
      if (disabled != null) state = { ...state, disabled };
      render();
    },
    dispose() {
      container.removeEventListener('input', onInput);
      container.innerHTML = '';
    },
  };
}

function injectStyles() {
  if (document.getElementById(STYLE_ID)) return;
  const link = document.createElement('link');
  link.id = STYLE_ID;
  link.rel = 'stylesheet';
  link.href = '/web/design-system/form-section.css';
  document.head.appendChild(link);
}
