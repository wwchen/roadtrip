import { jsonGetOk, jsonPostOk } from './http.js';

const MASKED_SECRET = '\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022';

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
