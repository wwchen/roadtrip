import {
  createReservableAvailabilityPollerForRid,
  fetchReservableAvailability,
} from '../api/reservable-api.js';
import {
  availabilityPollerFromQuery,
  availabilityQueryFromForm,
  defaultAvailabilityQuery,
} from './availability-panel.js';

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
        pollersByDate: Object.create(null),
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
    state.pollersByDate = Object.create(null);
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

  async function createPoller(rid, targetDate) {
    const state = stateForRid(rid);
    const date = String(targetDate || '').trim();
    if (!date) return;
    const dateState = state.pollersByDate[date] || {};
    dateState.abort?.abort();
    dateState.abort = new AbortController();
    dateState.loading = true;
    dateState.error = '';
    dateState.poller = null;
    state.pollersByDate[date] = dateState;
    render();

    try {
      dateState.poller = await createReservableAvailabilityPollerForRid(
        rid,
        availabilityPollerFromQuery(state.query, date),
        { signal: dateState.abort.signal },
      );
    } catch (err) {
      if (err.name === 'AbortError') return;
      dateState.error = errorMessage(err);
    } finally {
      dateState.loading = false;
      render();
    }
  }

  return {
    stateForRow,
    toggleAvailability,
    queryAvailability,
    createPoller,
  };
}

function errorMessage(err) {
  if (err?.status) return `Request failed: HTTP ${err.status}`;
  return err?.message || 'Request failed';
}
