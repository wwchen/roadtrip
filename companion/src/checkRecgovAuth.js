// Real Recreation.gov auth probe for the companion. Opens the persistent
// Chromium profile, waits for login when needed, and exits non-zero if the SPA
// still does not accept the session.

process.env.HEADLESS = 'false'

const { testChromium } = await import('./cart.js')

console.log('Rec.gov auth check: opening companion Chromium profile')
console.log('Rec.gov auth check: log in if the browser prompts; waiting uses RECGOV_LOGIN_TIMEOUT_MS when set')

try {
  const result = await testChromium()
  if (result?.loggedIn === true) {
    console.log('REC_GOV_AUTH_OK')
    process.exit(0)
  }

  console.error('REC_GOV_AUTH_FAILED')
  process.exit(1)
} catch (err) {
  console.error(`REC_GOV_AUTH_ERROR ${err.message}`)
  process.exit(1)
}
