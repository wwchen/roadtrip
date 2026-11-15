import fs from 'node:fs'
import path from 'node:path'
import { chromium } from 'playwright'
import { COMPANION_USER_AGENT } from './browser.js'
import { RECGOV_SCREENSHOT_VIEWPORT } from './recgovScreenshotCapture.js'
import {
  RECGOV_HOME_URL,
  RECGOV_LOGIN_NAVIGATION_TIMEOUT_MS,
  RECGOV_LOGIN_STATE_SETTLE_MS,
} from './recgovSession.js'
import { describeProbeResult, probeRecgovLoginFields } from './recgovLoginProbe.js'

const DEFAULT_SCREENSHOT_DIR = 'recgov-login-probe'
const SCREENSHOT_FILE = 'recgov-login-probe-failure.png'

const screenshotDir = process.env.RECGOV_LOGIN_PROBE_SCREENSHOT_DIR || DEFAULT_SCREENSHOT_DIR

const browser = await chromium.launch({
  channel: 'chromium',
  headless: true,
  args: ['--disable-blink-features=AutomationControlled'],
  ignoreDefaultArgs: ['--enable-automation'],
})
const page = await browser.newPage({ viewport: RECGOV_SCREENSHOT_VIEWPORT, userAgent: COMPANION_USER_AGENT })
let exitCode = 1
try {
  await page.goto(RECGOV_HOME_URL, { waitUntil: 'domcontentloaded', timeout: RECGOV_LOGIN_NAVIGATION_TIMEOUT_MS })
  await page.waitForTimeout(RECGOV_LOGIN_STATE_SETTLE_MS)
  const result = await probeRecgovLoginFields(page)
  for (const line of describeProbeResult(result)) console.log(line)
  if (result.ok) exitCode = 0
} catch (err) {
  console.error(`RECGOV_LOGIN_PROBE_ERROR ${err.message}`)
}
if (exitCode !== 0) {
  fs.mkdirSync(screenshotDir, { recursive: true })
  const file = path.join(screenshotDir, SCREENSHOT_FILE)
  await page.screenshot({ path: file }).catch((err) => console.error(`screenshot failed: ${err.message}`))
  console.error(`screenshot=${file}`)
}
await browser.close()
process.exit(exitCode)
