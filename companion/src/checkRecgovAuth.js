// Real Recreation.gov auth probe for the companion. Opens the persistent
// Chromium profile, waits for login when needed, and exits non-zero if the SPA
// still does not accept the session.

process.env.HEADLESS = 'false'

const { testChromium } = await import('./cart.js')
const { profileIdForSessionDir } = await import('./profilePool.js')
const args = new Set(process.argv.slice(2))
const forceRefresh = args.has('--force-refresh')

// Point COMPANION_BROWSER_PROFILE at a pool directory (or set
// COMPANION_PROFILE_ID) and the minted cookie jar is stored under that
// profile's key — which is how a headed login on the host survives into the
// container. Without one, this stays the legacy unkeyed single-profile run.
const profileId = profileIdForSessionDir()

console.log('Rec.gov auth check: opening companion Chromium profile')
console.log('Rec.gov auth check: log in manually if the browser prompts; waiting uses RECGOV_LOGIN_TIMEOUT_MS when set')
console.log(profileId
  ? `Rec.gov auth check: session will be stored for profile ${profileId}`
  : 'Rec.gov auth check: no profile id resolved — storing under the legacy single-profile key')
if (forceRefresh) console.log('Rec.gov auth check: forcing Recreation.gov token refresh')

try {
  const result = await testChromium(null, {
    forceRefresh,
    allowManualLoginAfterRefreshFailure: forceRefresh,
    profileId,
  })
  if (result?.loggedIn === true) {
    console.log(forceRefresh ? 'REC_GOV_AUTH_REFRESH_OK' : 'REC_GOV_AUTH_OK')
    process.exit(0)
  }

  console.error('REC_GOV_AUTH_FAILED')
  process.exit(1)
} catch (err) {
  console.error(`REC_GOV_AUTH_ERROR ${err.message}`)
  process.exit(1)
}
