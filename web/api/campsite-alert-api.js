import { jsonDeleteOk, jsonGetOk, jsonPostOk } from './http.js';

const MASKED_SECRET = '••••••••';

export async function validateCampsiteAlertIntegrations({ autoCart, notifySlack, signal } = {}) {
  const checks = [];
  if (notifySlack) checks.push(fetchSlackSettings(signal));
  if (autoCart) checks.push(validateBookingSession(signal));
  const results = await Promise.all(checks);
  return results.flat();
}

export function createCampsiteAlert(payload, { signal } = {}) {
  return jsonPostOk('/api/campsite/alerts', payload, { signal });
}

export function listCampsiteAlerts({ signal } = {}) {
  return jsonGetOk('/api/campsite/alerts', { signal });
}

export function deleteCampsiteAlert(id, { signal } = {}) {
  return jsonDeleteOk(`/api/campsite/alerts/${encodeURIComponent(id)}`, { signal });
}

/**
 * Find an active alert that already covers (campgroundId, startDate, minNights).
 * Used by the day-detail toggle so a second click on a watched day removes the
 * existing alert instead of creating a duplicate. Returns the matching alert
 * row or null.
 *
 * @param {Array}  alerts  output of listCampsiteAlerts (or a cached snapshot)
 * @param {object} match   { campgroundId, startDate, minNights }
 */
export function findMatchingAlert(alerts, { campgroundId, startDate, minNights }) {
  if (!Array.isArray(alerts)) return null;
  const cgId = String(campgroundId);
  return (
    alerts.find(
      (a) =>
        a &&
        a.status !== 'done' &&
        String(a.campground_id ?? a.campgroundId) === cgId &&
        (a.start_date ?? a.startDate) === startDate &&
        Number(a.min_nights ?? a.minNights ?? 1) === Number(minNights),
    ) || null
  );
}

async function fetchSlackSettings(signal) {
  try {
    const settings = await jsonGetOk('/api/campsite/settings', { signal });
    const hasToken = settings.slack_token === MASKED_SECRET;
    const hasChannel = Boolean(settings.slack_channel);
    return hasToken && hasChannel
      ? []
      : ['Slack notifications need a token and channel in campsite settings.'];
  } catch (error) {
    if (error.name === 'AbortError') throw error;
    return ['Could not verify Slack settings.'];
  }
}

async function validateBookingSession(signal) {
  try {
    const result = await jsonPostOk('/api/campsite/booking/session/validate', {}, { signal });
    if (result?.loggedIn) return [];
    const suffix = result?.error ? ` (${result.error})` : '';
    return [`Auto-cart needs a valid rec.gov token in campsite settings.${suffix}`];
  } catch (error) {
    if (error.name === 'AbortError') throw error;
    return [`Could not verify rec.gov session (${error.message}).`];
  }
}
