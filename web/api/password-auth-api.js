// web/api/password-auth-api.js
//
// Client for the embedded password endpoints. The session lands as an HttpOnly
// cookie set by /auth/password/complete; nothing sensitive is returned to script.
const BEGIN_URL = '/auth/password/begin';
const COMPLETE_URL = '/auth/password/complete';
const CREDENTIALS = 'same-origin';

async function postJson(url, body, _fetch) {
  const response = await _fetch(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
    credentials: CREDENTIALS,
  });
  if (!response.ok) {
    let code;
    try { code = (await response.json())?.error; } catch { /* non-JSON body */ }
    const err = new Error(`${url}: HTTP ${response.status}`);
    err.code = code;
    throw err;
  }
  return response.status === 204 ? null : response.json();
}

export function beginPasswordLogin(returnTo, { _fetch = fetch } = {}) {
  return postJson(BEGIN_URL, { return_to: returnTo }, _fetch);
}

export function completePasswordLogin(code, state, returnTo, { _fetch = fetch } = {}) {
  return postJson(COMPLETE_URL, { code, state, return_to: returnTo }, _fetch);
}
