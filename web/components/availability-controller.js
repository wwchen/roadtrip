import {
  fetchReservableAvailabilityLogs,
  queryReservableAvailability,
} from '../api/reservable-api.js';
import {
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
      const queryBody = availabilityIntentQueryForRid(rid, state.query);
      const response = await queryReservableAvailability(queryBody, { signal: state.abort.signal });
      const logs = await fetchReservableAvailabilityLogs({
        run_id: response.run_id,
        rid,
        limit: state.query.days,
        signal: state.abort.signal,
      });
      state.data = availabilityResultFromIntent(rid, state.query, response, logs);
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

function availabilityIntentQueryForRid(rid, query) {
  return {
    scope: { rid },
    start_date: query.start,
    days: numberValue(query.days, 7),
    min_nights: numberValue(query.minNights, 1),
    force: !!query.force,
  };
}

function availabilityResultFromIntent(rid, query, response, logsResponse) {
  const result = (response.results || []).find((row) => row.reservable?.rid === rid) || {};
  const logs = Array.isArray(logsResponse.logs) ? logsResponse.logs : [];
  const availability = logs
    .slice()
    .sort((a, b) => String(a.target_date || '').localeCompare(String(b.target_date || '')))
    .map((log) => ({
      date: log.day_payload?.date || log.target_date,
      status: log.day_payload?.status || log.status || 'unknown',
      available_count: log.day_payload?.available_count ?? (log.available ? 1 : 0),
      total: log.day_payload?.total ?? 1,
      available_reservable_ids: log.day_payload?.available_reservable_ids || [],
    }));
  const matching = result.matching_starts?.length || 0;
  const partial = result.partial_starts?.length || 0;
  const summary =
    matching > 0
      ? `${matching} matching start${matching === 1 ? '' : 's'}`
      : partial > 0
        ? `${partial} partial start${partial === 1 ? '' : 's'}`
        : `No matching starts across ${query.days} day${String(query.days) === '1' ? '' : 's'}`;
  return {
    provider: result.reservable?.vendor || '',
    reservable_id: rid,
    checked_at: response.observed_at,
    window: {
      start: query.start,
      days: numberValue(query.days, 7),
    },
    summary,
    state: matching > 0 ? 'available' : partial > 0 ? 'partial' : 'zero_available',
    availability,
    intent_query: response,
    logs: logsResponse,
  };
}

function errorMessage(err) {
  if (err?.status) return `Request failed: HTTP ${err.status}`;
  return err?.message || 'Request failed';
}

function numberValue(value, fallback) {
  const n = Number(value);
  return Number.isFinite(n) ? n : fallback;
}
