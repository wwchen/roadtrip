import { segmentedControl } from '../templates/segmented-control.js';

// The radios need a shared `name` to behave as one group. When the caller does
// not supply one, this counter does — unique within the document, and stable for
// the life of the control.
let seq = 0;

export function mountSegmentedControl(container, config = {}) {
  let cfg = { ...config };
  const name = cfg.name || `lds-seg-${seq++}`;
  let value = cfg.value ?? cfg.defaultValue;

  const render = () => { container.innerHTML = segmentedControl({ ...cfg, name, value }); };

  // `change` is delegated: the radios are replaced on every re-render, so
  // binding each one would leak a listener per paint.
  const onChange = (e) => {
    const el = e.target;
    if (!el.matches || !el.matches('.lds-seg__option input')) return;
    value = el.value;
    if (cfg.onChange) cfg.onChange(value);
  };

  container.addEventListener('change', onChange);
  render();

  return {
    get value() { return value; },
    update(next = {}) {
      cfg = { ...cfg, ...next };
      if (next.value !== undefined) value = next.value;
      render();
    },
    dispose() {
      container.removeEventListener('change', onChange);
      container.innerHTML = '';
    },
  };
}
