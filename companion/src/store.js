// Local JSON-file store for companion-side state.
//
// The companion no longer stores recgov tokens or refresh creds in this JSON
// file. Its persistent Chromium profile is the login source of truth, including
// localStorage.recaccount and refresh lifecycle.
//
// What still lives here: `recgov_cookies` (paste-derived cookie string used
// for the Akamai TLS-fingerprint workaround in the Playwright browser
// context). The cookies must stay local because they're tied to the same
// browser session that runs ATC.

import fs from 'node:fs'
import path from 'node:path'
import os from 'node:os'

// Resolved per call, not at import: the location is environment, and binding
// it at module load meant any test that imported this transitively — directly
// or through server.js — wrote to the developer's real store.
function storeDir () {
  return process.env.COMPANION_DIR || path.join(os.homedir(), '.campsite-companion')
}

function storePath () {
  return path.join(storeDir(), 'store.json')
}

function read () {
  const file = storePath()
  if (!fs.existsSync(file)) return {}
  try { return JSON.parse(fs.readFileSync(file, 'utf8')) } catch { return {} }
}

function write (data) {
  const dir = storeDir()
  if (!fs.existsSync(dir)) fs.mkdirSync(dir, { recursive: true })
  fs.writeFileSync(path.join(dir, 'store.json'), JSON.stringify(data, null, 2))
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
