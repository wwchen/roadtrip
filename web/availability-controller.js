import { fetchReservableAvailability } from './api/reservable-api.js';
import {
  availabilityQueryFromForm,
  defaultAvailabilityQuery,
} from './availability-components.js';

export function createAvailabilityPanels({ render }) {
  const panels = new Map();

  function stateForRid(rid) {
    if (!panels.has(rid)) {
      panels.set(rid, {
        expanded: false,
        query: defaultAvailabilityQuery(),
        loading: false,
        error: '',
        data: null,
        abort: null,
      });
    }
    return panels.get(rid);
  }

  function stateForRow(row) {
    return panels.get(row.rid);
  }

  function toggleAvailability(rid) {
    const state = stateForRid(rid);
    state.expanded = !state.expanded;
    render();
  }

  async function queryAvailability(rid, formEl) {
    const state = stateForRid(rid);
    state.abort?.abort();
    state.abort = new AbortController();
    state.query = availabilityQueryFromForm(formEl);
    state.loading = true;
    state.error = '';
    state.data = null;
    render();

    try {
      state.data = await fetchReservableAvailability(rid, {
        ...state.query,
        signal: state.abort.signal,
      });
    } catch (err) {
      if (err.name === 'AbortError') return;
      state.error = errorMessage(err);
    } finally {
      state.loading = false;
      render();
    }
  }

  return {
    stateForRow,
    toggleAvailability,
    queryAvailability,
  };
}

function errorMessage(err) {
  if (err?.status) return `Request failed: HTTP ${err.status}`;
  return err?.message || 'Request failed';
}
