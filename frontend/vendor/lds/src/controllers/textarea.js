import { textarea } from '../templates/textarea.js';

export function mountTextarea(container, config = {}) {
  let cfg = { ...config };
  let value = String(cfg.value ?? '');

  const render = () => { container.innerHTML = textarea({ ...cfg, value }); };

  // The counter is patched in place rather than re-rendered — re-rendering a
  // textarea on every keystroke loses the caret position.
  const paint = () => {
    const el = container.querySelector('.lds-field__count');
    if (!el) return;
    const n = value.length;
    el.textContent = cfg.maxLength !== undefined ? `${n} / ${cfg.maxLength}` : String(n);
    el.classList.toggle('lds-field__count--over', cfg.maxLength !== undefined && n >= cfg.maxLength);
  };

  const onInput = (e) => {
    if (!e.target.matches || !e.target.matches('textarea')) return;
    value = e.target.value;
    paint();
    if (cfg.onChange) cfg.onChange(e);
  };

  container.addEventListener('input', onInput);
  render();

  return {
    get value() { return value; },
    update(next = {}) {
      cfg = { ...cfg, ...next };
      if (next.value !== undefined) value = String(next.value);
      render();
    },
    dispose() {
      container.removeEventListener('input', onInput);
      container.innerHTML = '';
    },
  };
}
