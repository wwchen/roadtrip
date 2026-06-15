import {
  createReservableAvailabilityPollerForRid,
  fetchReservableAvailability,
} from '../api/reservable-api.js';
import {
  availabilityPollerFromForm,
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
        poller: null,
        pollerError: '',
        pollerLoading: false,
        abort: null,
        pollerAbort: null,
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

  async function createPoller(rid, formEl) {
    const state = stateForRid(rid);
    state.pollerAbort?.abort();
    state.pollerAbort = new AbortController();
    state.pollerLoading = true;
    state.pollerError = '';
    state.poller = null;
    render();

    try {
      state.poller = await createReservableAvailabilityPollerForRid(
        rid,
        availabilityPollerFromForm(formEl),
        { signal: state.pollerAbort.signal },
      );
    } catch (err) {
      if (err.name === 'AbortError') return;
      state.pollerError = errorMessage(err);
    } finally {
      state.pollerLoading = false;
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
