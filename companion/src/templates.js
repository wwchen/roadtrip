import { readFileSync } from 'node:fs'

const LOGIN_FORM_TITLE = 'Recreation.gov Login'
const LOGIN_PAGE_TEMPLATE = readTemplate('./loginPage.html')
const REFRESH_PAGE_TEMPLATE = readTemplate('./refreshPage.html')
const LOGIN_DIAGNOSTIC_TEMPLATE = readTemplate('./loginDiagnostic.html')
const SWAGGER_PAGE_TEMPLATE = readTemplate('./swaggerPage.html')

export function renderLoginPage ({ result = null } = {}) {
  const status = result?.recgov_auth
  const ok = result?.ok === true
  const error = result && !ok ? result.detail || status?.detail || result.error || status?.error : null
  const operation = status?.operation === 'refresh' ? 'Refresh' : 'Login'
  const diagnostic = status?.diagnostic || status?.last_login_diagnostic || null
  const statusHtml = result
    ? `<p id="status-message" class="${ok ? 'ok' : 'error'}">${escapeHtml(ok ? `${operation} succeeded.` : `${operation} failed: ${error}`)}</p>`
    : '<p id="status-message" class="muted">Ready.</p>'
  const initialJson = result ? JSON.stringify(result, null, 2) : ''
  const jsonClass = result ? '' : ' hidden'
  const diagnosticHtml = diagnostic?.screenshot_url
    ? renderDiagnosticHtml(diagnostic)
    : ''

  return renderTemplate(LOGIN_PAGE_TEMPLATE, {
    LOGIN_FORM_TITLE: escapeHtml(LOGIN_FORM_TITLE),
    STATUS_HTML: statusHtml,
    JSON_CLASS: jsonClass,
    INITIAL_JSON: escapeHtml(initialJson),
    DIAGNOSTIC_HTML: diagnosticHtml,
  })
}

export function renderRefreshPage () {
  return REFRESH_PAGE_TEMPLATE
}

export function renderSwaggerPage () {
  return SWAGGER_PAGE_TEMPLATE
}

function renderDiagnosticHtml (diagnostic) {
  return renderTemplate(LOGIN_DIAGNOSTIC_TEMPLATE, {
    DIAGNOSTIC_REASON: escapeHtml(diagnostic.reason || 'unknown'),
    DIAGNOSTIC_SCREENSHOT_URL: escapeHtml(diagnostic.screenshot_url),
  })
}

function renderTemplate (template, values) {
  return template.replace(/\{\{([A-Z_]+)\}\}/g, (_match, key) => String(values[key] ?? ''))
}

function readTemplate (filename) {
  return readFileSync(new URL(filename, import.meta.url), 'utf8')
}

function escapeHtml (value) {
  return String(value ?? '')
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;')
    .replaceAll("'", '&#39;')
}
