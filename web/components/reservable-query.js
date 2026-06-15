export function mountReservableQuery(root, { onSubmit, onReset } = {}) {
  root.innerHTML = reservableQueryHtml();
  const form = root.querySelector('form');
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

function reservableQueryHtml() {
  return `
    <form id="reservable-form" class="panel" autocomplete="off">
      <div class="filters">
        <label>
          Type
          <select name="type">
            <option value="site">site</option>
            <option value="">any</option>
          </select>
        </label>
        <label>
          Vendor
          <input name="vendor" placeholder="recgov, aspira_pc">
        </label>
        <label>
          Vendor ID
          <input name="vendor_id" placeholder="330257">
        </label>
        <label>
          Name
          <input name="name" placeholder="A12">
        </label>
        <label>
          Loop
          <input name="loop" placeholder="Loop A">
        </label>
        <label>
          Site Type
          <input name="site_type" placeholder="STANDARD">
        </label>
        <label class="wide">
          ID
          <input name="id" placeholder="site:recgov:330257">
        </label>
        <label class="wide">
          Raw contains
          <textarea name="raw" spellcheck="false" placeholder='{"host":"reservation.pc.gc.ca"}'></textarea>
        </label>
        <label>
          Limit
          <select name="limit">
            <option value="50">50</option>
            <option value="100" selected>100</option>
            <option value="250">250</option>
            <option value="500">500</option>
          </select>
        </label>
        <div class="actions">
          <button class="primary" type="submit">Search</button>
          <button data-action="reset-reservable-query" type="button">Reset</button>
        </div>
      </div>
    </form>
  `;
}
