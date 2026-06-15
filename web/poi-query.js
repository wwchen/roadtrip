export function mountPoiQuery(root, { onSubmit, onReset } = {}) {
  root.innerHTML = poiQueryHtml();
  const form = root.querySelector('form');
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

function poiQueryHtml() {
  return `
    <form id="poi-form" class="panel" autocomplete="off">
      <div class="filters">
        <label>
          ID
          <input name="id" inputmode="numeric" placeholder="64397">
        </label>
        <label>
          Name
          <input name="q" placeholder="Glacier, Upper Pines, Planet Fitness">
        </label>
        <label>
          Categories
          <input name="categories" placeholder="campground, supercharger">
        </label>
        <label>
          Limit
          <select name="limit">
            <option value="10">10</option>
            <option value="25" selected>25</option>
          </select>
        </label>
        <div class="actions">
          <button class="primary" type="submit">Search</button>
          <button data-action="reset-poi-query" type="button">Reset</button>
        </div>
      </div>
    </form>
  `;
}
