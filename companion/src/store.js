// Local JSON-file store for companion-side secrets (recgov_token, refresh creds).
// The backend never sees these. Anything else (poll_interval, slack_*) lives in
// the backend's settings table.

import fs from 'node:fs'
import path from 'node:path'
import os from 'node:os'

const STORE_DIR = process.env.COMPANION_DIR
  || path.join(os.homedir(), '.campsite-companion')
const STORE_PATH = path.join(STORE_DIR, 'store.json')

function ensureDir () {
  if (!fs.existsSync(STORE_DIR)) fs.mkdirSync(STORE_DIR, { recursive: true })
}

function read () {
  if (!fs.existsSync(STORE_PATH)) return {}
  try { return JSON.parse(fs.readFileSync(STORE_PATH, 'utf8')) } catch { return {} }
}

function write (data) {
  ensureDir()
  fs.writeFileSync(STORE_PATH, JSON.stringify(data, null, 2))
}

export function getSetting (key) {
  return read()[key] ?? null
}

export function setSetting (key, value) {
  const data = read()
  data[key] = value == null ? null : String(value)
  write(data)
}

export function getAll () {
  return read()
}

// Bootstraps recgov auth from RECGOV_RECACCOUNT (full localStorage recaccount JSON).
// refresh_id is stable so we always overwrite; token is only seeded if missing.
export function seedFromEnv () {
  const raw = process.env.RECGOV_RECACCOUNT
  if (!raw) return
  try {
    const ra = JSON.parse(raw)
    if (ra.refresh_id && ra.account?.account_id) {
      setSetting('recgov_refresh_creds', JSON.stringify({
        account_id: ra.account.account_id,
        refresh_id: ra.refresh_id,
      }))
    }
    if (ra.access_token && !getSetting('recgov_token')) {
      setSetting('recgov_token', ra.access_token)
    }
  } catch (e) {
    console.warn('RECGOV_RECACCOUNT parse failed:', e.message)
  }
}
