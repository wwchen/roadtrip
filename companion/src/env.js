import { readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const REPO_ENV_PATH = resolve(dirname(fileURLToPath(import.meta.url)), '..', '..', '.env')
const DISPATCH_TOKEN_KEYS = ['DISPATCH_COMPANION_TOKEN', 'COMPANION_DISPATCH_TOKEN']

let repoEnvCache = null

export function parseDotenv (text) {
  const values = {}
  for (const rawLine of text.split(/\r?\n/)) {
    const line = rawLine.trim()
    if (!line || line.startsWith('#') || !line.includes('=')) continue

    let [key, ...rest] = line.split('=')
    key = key.trim().replace(/^export\s+/, '')
    if (!key || Object.hasOwn(values, key)) continue

    let value = rest.join('=').trim()
    const quoted = (value.startsWith('"') && value.endsWith('"')) ||
      (value.startsWith("'") && value.endsWith("'"))
    if (quoted) value = value.slice(1, -1)
    values[key] = value
  }
  return values
}

export function repoDotenv () {
  if (repoEnvCache) return repoEnvCache
  try {
    repoEnvCache = parseDotenv(readFileSync(REPO_ENV_PATH, 'utf8'))
  } catch {
    repoEnvCache = {}
  }
  return repoEnvCache
}

export function dispatchCompanionToken (env = process.env, dotenv = null) {
  for (const key of DISPATCH_TOKEN_KEYS) {
    if (env[key]) return env[key]
  }

  const values = dotenv ?? repoDotenv()
  for (const key of DISPATCH_TOKEN_KEYS) {
    if (values[key]) return values[key]
  }
  return ''
}
