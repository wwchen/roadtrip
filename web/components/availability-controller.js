import { fetchReservableAvailability } from '../api/reservable-api.js';
import {
  availabilityQueryFromForm,
  defaultAvailabilityQuery,
} from './availability-panel.js';

export function createAvailabilityPanels({ render }) {
  const panels = new Map();

  function stateForRid(rid) {
    if (!panels.has(rid)) {
      panels.set(rid, {
        mode: null,
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

  function toggleDetails(rid) {
    const state = stateForRid(rid);
    state.mode = state.mode === 'details' ? null : 'details';
    render();
  }

  function toggleAvailability(rid) {
    const state = stateForRid(rid);
    const opening = state.mode !== 'availability';
    state.mode = opening ? 'availability' : null;
    render();
    if (opening && !state.data && !state.loading) {
      queryAvailability(rid);
    }
  }

  async function queryAvailability(rid, formEl = null) {
    const state = stateForRid(rid);
    state.abort?.abort();
    state.abort = new AbortController();
    state.mode = 'availability';
    state.query = formEl ? availabilityQueryFromForm(formEl) : state.query;
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
    toggleDetails,
    toggleAvailability,
    queryAvailability,
  };
}

function errorMessage(err) {
  if (err?.status) return `Request failed: HTTP ${err.status}`;
  return err?.message || 'Request failed';
}
