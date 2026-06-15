export function mountPoiQuery(root, { onSubmit, onReset } = {}) {
  const form = root.querySelector('form');
  if (!form) throw new Error('POI query markup is missing');
  const resetBtn = root.querySelector('[data-action="reset-poi-query"]');

  form.addEventListener('submit', (event) => {
    event.preventDefault();
    onSubmit?.();
  });
  resetBtn.addEventListener('click', () => {
    form.reset();
    onReset?.();
  });

  return {
    form,
    params() {
      const data = new FormData(form);
      const params = {};
      for (const [key, value] of data.entries()) {
        const text = String(value).trim();
        if (!text) continue;
        params[key] = text;
      }
      return params;
    },
    applyParamsFromUrl(search = window.location.search) {
      const qs = new URLSearchParams(search);
      for (const el of form.elements) {
        if (!el.name || !qs.has(el.name)) continue;
        el.value = qs.get(el.name) || '';
      }
    },
    setBusy(busy) {
      form.querySelectorAll('input, select, button').forEach((el) => {
        el.disabled = busy;
      });
    },
  };
}
