// Real Recreation.gov auth probe for the companion. Opens the persistent
// Chromium profile, waits for login when needed, and exits non-zero if the SPA
// still does not accept the session.

process.env.HEADLESS = 'false'

const { testChromium } = await import('./cart.js')
const args = new Set(process.argv.slice(2))
const forceRefresh = args.has('--force-refresh')

console.log('Rec.gov auth check: opening companion Chromium profile')
console.log('Rec.gov auth check: set RECGOV_EMAIL + RECGOV_PASSWORD to attempt credential login')
console.log('Rec.gov auth check: set RECGOV_MFA_CODE or RECGOV_OTP when Recreation.gov requires 2FA')
console.log('Rec.gov auth check: log in manually if the browser prompts; waiting uses RECGOV_LOGIN_TIMEOUT_MS when set')
if (forceRefresh) console.log('Rec.gov auth check: forcing Recreation.gov token refresh')

try {
  const result = await testChromium(null, {
    forceRefresh,
    allowManualLoginAfterRefreshFailure: forceRefresh,
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
