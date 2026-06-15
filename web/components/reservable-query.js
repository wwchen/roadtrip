export function mountReservableQuery(root, { onSubmit, onReset } = {}) {
  const form = root.querySelector('form');
  if (!form) throw new Error('Reservable query markup is missing');
  const resetBtn = root.querySelector('[data-action="reset-reservable-query"]');

  form.addEventListener('submit', (event) => {
    event.preventDefault();
    onSubmit?.();
  });
  resetBtn.addEventListener('click', () => {
    form.reset();
    form.elements.type.value = 'site';
    onReset?.();
  });

  return {
    form,
    params(offset = 0) {
      const data = new FormData(form);
      const params = {};
      for (const [key, value] of data.entries()) {
        const text = String(value).trim();
        if (!text) continue;
        params[key === 'id' ? 'id' : key] = text;
      }
      params.offset = String(offset);
      return params;
    },
    applyParamsFromUrl(search = window.location.search) {
      const qs = new URLSearchParams(search);
      for (const el of form.elements) {
        if (!el.name) continue;
        if (qs.has(el.name)) {
          el.value = qs.get(el.name) || '';
        } else if (el.name === 'id' && qs.has('rid')) {
          el.value = qs.get('rid') || '';
        } else if (el.name === 'poi_id' && qs.has('poiId')) {
          el.value = qs.get('poiId') || '';
        }
      }
    },
    limitValue() {
      return Math.max(1, parseInt(form.elements.limit.value || '100', 10) || 100);
    },
    setBusy(busy, { offset = 0, total = 0 } = {}) {
      form.querySelectorAll('input, select, textarea, button').forEach((el) => {
        el.disabled = busy;
      });
      const limit = this.limitValue();
      return {
        prevDisabled: busy || offset <= 0,
        nextDisabled: busy || offset + limit >= total,
      };
    },
  };
}
