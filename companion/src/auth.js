// JWT utilities, cURL parsing, and the rec.gov token refresh API.
// Pure ESM; no Playwright dependency.

import { getSetting, setSetting } from './store.js'

export function jwtExpiry (token) {
  try {
    const payload = JSON.parse(Buffer.from(token.split('.')[1], 'base64url').toString())
    return payload.exp ? new Date(payload.exp * 1000) : null
  } catch { return null }
}

export function jwtFingerprint (token) {
  try {
    const payload = JSON.parse(Buffer.from(token.split('.')[1], 'base64url').toString())
    return payload.fingerprint || ''
  } catch { return '' }
}

export function extractCookiesFromInput (input) {
  const s = (input || '').trim()
  if (!s.startsWith('curl ')) return s
  const bMatch = s.match(/(?:-b|--cookie)\s+['"]([^'"]*)['"]/s)
  if (bMatch) return bMatch[1].trim()
  const hMatch = s.match(/-H\s+['"][Cc]ookie:\s*([^'"]*)['"]/s)
  if (hMatch) return hMatch[1].trim()
  return s
}

export function extractBearerFromInput (input) {
  const match = (input || '').match(/-H\s+['"][Aa]uthorization:\s+[Bb]earer\s+([A-Za-z0-9._-]+)/)
  return match ? match[1] : null
}

export function extractRefreshCredsFromInput (input) {
  const s = (input || '').trim()
  const rawMatch = s.match(/--data-raw\s+['"](\{[^'"]*\})['"]/)
  if (rawMatch) {
    try {
      const body = JSON.parse(rawMatch[1])
      if (body.account_id && body.refresh_id) return { account_id: body.account_id, refresh_id: body.refresh_id }
    } catch {}
  }
  try {
    const obj = JSON.parse(s)
    if (obj.account_id && obj.refresh_id) return { account_id: obj.account_id, refresh_id: obj.refresh_id }
    if (obj.refresh_id && obj.account?.account_id) return { account_id: obj.account.account_id, refresh_id: obj.refresh_id }
  } catch {}
  return null
}

export function buildRecaccountFromToken (token) {
  try {
    const payload = JSON.parse(Buffer.from(token.split('.')[1], 'base64url').toString())
    const exp = payload.exp ? new Date(payload.exp * 1000) : new Date(Date.now() + 30 * 60 * 1000)
    return {
      access_token: token,
      expiration: exp.toISOString(),
      account: {
        account_id: payload.acct?.account_id || payload.sub || '',
        email: payload.acct?.email || '',
        first_name: payload.acct?.first_name || '',
        last_name: payload.acct?.last_name || '',
      },
      is_guest: false,
      refresh_id: '',
    }
  } catch { return null }
}

export async function refreshRecgovSession () {
  const token = getSetting('recgov_token') || ''
  const credsStr = getSetting('recgov_refresh_creds') || ''
  if (!token) return null

  if (!credsStr) {
    const exp = jwtExpiry(token)
    if (exp && exp > new Date()) {
      console.log(`Auth: no refresh creds — using stored token (expires ${exp.toISOString()})`)
      return buildRecaccountFromToken(token)
    }
    return null
  }

  let creds
  try { creds = JSON.parse(credsStr) } catch { return null }
  const { account_id, refresh_id } = creds
  if (!account_id || !refresh_id) return null

  const fingerprint = jwtFingerprint(token)
  const headers = {
    Authorization: `Bearer ${token}`,
    'Content-Type': 'text/plain;charset=UTF-8',
  }
  if (fingerprint) headers.Cookie = `r1s-fingerprint=${fingerprint}`

  try {
    console.log(`Auth: POST /api/accounts/login/v2/refresh (account ${account_id})`)
    const resp = await fetch('https://www.recreation.gov/api/accounts/login/v2/refresh', {
      method: 'POST',
      headers,
      body: JSON.stringify({ account_id, refresh_id }),
    })
    if (!resp.ok) {
      const body = await resp.text().catch(() => '')
      console.log(`Auth: token refresh failed (HTTP ${resp.status}) — ${body}`)
      return null
    }
    const recaccount = await resp.json()
    setSetting('recgov_token', recaccount.access_token)
    console.log(`Auth: token refreshed — expires ${recaccount.expiration}`)
    return recaccount
  } catch (err) {
    console.log(`Auth: token refresh error — ${err.message}`)
    return null
  }
}
