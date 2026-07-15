// Local JSON-file store for companion-side state.
//
// The companion no longer stores recgov tokens or refresh creds in this JSON
// file. Its persistent Chromium profile is the login source of truth, and the
// backend owns refresh after the companion imports localStorage.recaccount via
// POST /api/campsite/booking/session/import.
//
// What still lives here: `recgov_cookies` (paste-derived cookie string used
// for the Akamai TLS-fingerprint workaround in the Playwright browser
// context). The cookies must stay local because they're tied to the same
// browser session that runs ATC.

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
