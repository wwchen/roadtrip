import {
  LOGIN_EMAIL_SELECTORS,
  LOGIN_LINK_SEL,
  LOGIN_PASSWORD_SELECTORS,
  LOGIN_SUBMIT_SELECTORS,
  firstVisibleInOrder,
  openLoginIfPossible,
} from './recgovSession.js'

export const LOGIN_LINK_FIELD = 'login_link'

export const RECGOV_LOGIN_PROBE_FIELDS = Object.freeze([
  { name: 'email', selectors: LOGIN_EMAIL_SELECTORS },
  { name: 'password', selectors: LOGIN_PASSWORD_SELECTORS },
  { name: 'submit', selectors: LOGIN_SUBMIT_SELECTORS },
])

export async function probeRecgovLoginFields (page) {
  if (!await openLoginIfPossible(page)) {
    return { ok: false, fields: { [LOGIN_LINK_FIELD]: null }, missing: [LOGIN_LINK_FIELD] }
  }
  const fields = { [LOGIN_LINK_FIELD]: LOGIN_LINK_SEL }
  const missing = []
  for (const { name, selectors } of RECGOV_LOGIN_PROBE_FIELDS) {
    fields[name] = await firstVisibleInOrder(page, selectors)
    if (!fields[name]) missing.push(name)
  }
  return { ok: missing.length === 0, fields, missing }
}

export function describeProbeResult (result) {
  const lines = Object.entries(result.fields)
    .map(([name, selector]) => `field=${name} ${selector ? `selector=${selector}` : 'NOT FOUND'}`)
  lines.push(result.ok
    ? 'RECGOV_LOGIN_PROBE_OK'
    : `RECGOV_LOGIN_PROBE_FAILED missing=${result.missing.join(',')}`)
  return lines
}
