// cURL cookie parsing for the recgov_cookies setting.
//
// As of RFC 0001 / PR 6, the companion no longer holds recgov tokens or
// refreshes them — that lives in the backend's TokenManager. The cookie
// path is still the companion's because Akamai TLS fingerprinting requires
// the same browser session for both auth and ATC, and the cookies are
// stored locally to keep the recreation.gov-bound traffic local.

const curlCookieRe = /(?:-b|--cookie)\s+['"]([^'"]*)['"]/s
const curlCookieHeaderRe = /-H\s+['"][Cc]ookie:\s*([^'"]*)['"]/s

export function extractCookiesFromInput (input) {
  const s = (input || '').trim()
  if (!s.startsWith('curl ')) return s
  const bMatch = s.match(curlCookieRe)
  if (bMatch) return bMatch[1].trim()
  const hMatch = s.match(curlCookieHeaderRe)
  if (hMatch) return hMatch[1].trim()
  return s
}
