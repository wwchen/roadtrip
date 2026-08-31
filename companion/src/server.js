// HTTP executor for backend-owned one-shot companion work.
// The backend posts an ATC payload here; this process owns the browser profile
// and returns the same JSON result as the recgov:atc CLI.

import http from 'node:http'
import { pathToFileURL } from 'node:url'
import { IS_HEADLESS } from './browser.js'
import { testChromium } from './cart.js'
import { logoutRecgovBrowserSession } from './recgovSession.js'
import { createRecgovScreenshotDeps } from './recgovScreenshot.js'
import { runAtcOnce } from './runAtcOnce.js'
import {
  COMPANION_OPENAPI_SPEC,
} from './openapi.js'
import { matchCompanionRoute } from './apiContract.js'
import { installJsonConsole } from './jsonConsole.js'
import {
  renderLoginPage,
  renderSwaggerPage,
} from './templates.js'
import { handleAtc } from './server/routes/atc.js'
import {
  getRecgovAuthStatus,
  getRecgovHealthStatus,
  runRecgovAuthCheck,
  runStartupAuthCheck,
} from './server/authStatus.js'
import {
  handleLoginPost,
  handleLogout,
  handleRefresh,
} from './server/routes/auth.js'
import {
  DEFAULT_HOST,
  DEFAULT_PORT,
  HTTP_BAD_REQUEST,
  HTTP_INTERNAL_ERROR,
  HTTP_OK,
} from './server/constants.js'
import {
  htmlResponse,
  jsonResponse,
} from './server/http.js'
import { log } from './server/logging.js'
import {
  authorizeCompanionRequest,
  companionApiToken,
} from './server/apiToken.js'
import { createServerRuntime } from './server/runtime.js'
import {
  handleDiagnosticImage,
  handleLiveScreenshot,
} from './server/routes/screenshot.js'

export {
  getRecgovAuthStatus,
  getRecgovHealthStatus,
  runRecgovAuthCheck,
  runStartupAuthCheck,
}

const HOST = process.env.COMPANION_HOST || DEFAULT_HOST
const PORT = Number.parseInt(process.env.COMPANION_PORT || String(DEFAULT_PORT), 10)

export function createCompanionServer ({
  testChromiumFn = testChromium,
  runAtcOnceFn = runAtcOnce,
  logoutRecgovSessionFn = logoutRecgovBrowserSession,
  apiToken = companionApiToken(),
  ...screenshotOverrides
} = {}) {
  const runtime = createServerRuntime()
  const deps = {
    testChromiumFn,
    runAtcOnceFn,
    logoutRecgovSessionFn: () => logoutRecgovSessionFn({
      getContextFn: screenshotOverrides.getContextFn,
      isSpaLoggedInFn: screenshotOverrides.isSpaLoggedInFn,
    }),
    recgovScreenshotDeps: createRecgovScreenshotDeps(screenshotOverrides),
  }
  const companionServer = http.createServer(async (req, res) => {
    const url = new URL(req.url || '/', 'http://companion.local')
    const route = matchCompanionRoute(req.method, url.pathname)
    const rejection = authorizeCompanionRequest({ req, route, token: apiToken })
    if (rejection) {
      jsonResponse(res, rejection.status, rejection.body)
      return
    }
    if (route) {
      await handleContractRoute(route, req, res, url, runtime, deps)
      return
    }
    jsonResponse(res, HTTP_BAD_REQUEST, {
      ok: false,
      error: 'unsupported_route',
      detail: `${req.method} ${req.url}`,
    })
  })
  companionServer.companionRuntime = runtime
  return companionServer
}

async function handleContractRoute (route, req, res, url, runtime, deps) {
  const handler = CONTRACT_ROUTE_HANDLERS[route.operationId]
  if (handler) {
    await handler({ req, res, url, runtime, deps })
    return
  }
  jsonResponse(res, HTTP_INTERNAL_ERROR, {
    ok: false,
    error: 'unhandled_route',
    detail: `${route.method} ${route.path}`,
  })
}

const CONTRACT_ROUTE_HANDLERS = {
  getDiagnosticScreenshot: async ({ url, res }) => handleDiagnosticImage(url, res),
  getScreenshot: async ({ url, res, runtime, deps }) => handleLiveScreenshot(url, res, { runtime, ...deps }),
  getOpenApiJson: async ({ res }) => jsonResponse(res, HTTP_OK, COMPANION_OPENAPI_SPEC),
  getSwaggerDocs: async ({ res }) => htmlResponse(res, HTTP_OK, renderSwaggerPage()),
  getHealth: async ({ res, runtime }) => jsonResponse(res, HTTP_OK, { ok: true, busy: runtime.isBusy(), recgov_auth: getRecgovHealthStatus() }),
  getOperatorPage: async ({ res }) => htmlResponse(res, HTTP_OK, renderLoginPage()),
  postLogin: async ({ req, res, runtime, deps }) => handleLoginPost(req, res, { runtime, ...deps }),
  postLogout: async ({ req, res, runtime, deps }) => handleLogout(req, res, { runtime, ...deps }),
  postRefresh: async ({ req, res, runtime, deps }) => handleRefresh(req, res, { runtime, ...deps }),
  postAtc: async ({ req, res, runtime, deps }) => handleAtc(req, res, { runtime, ...deps }),
}

export const HANDLED_OPERATION_IDS = Object.freeze(Object.keys(CONTRACT_ROUTE_HANDLERS))

export const server = createCompanionServer()

export function startServer () {
  server.listen(PORT, HOST, () => {
    log('listening', `http://${HOST}:${PORT}`, `headless=${IS_HEADLESS}`)
    server.companionRuntime.setStartupAuthCheck(runStartupAuthCheck())
  })
  return server
}

function installShutdownHandlers (runningServer) {
  for (const sig of ['SIGINT', 'SIGTERM']) {
    process.on(sig, () => {
      log('shutting down')
      runningServer.close(() => process.exit(0))
      setTimeout(() => process.exit(0), 1000)
    })
  }
}

const entrypointUrl = process.argv[1] ? pathToFileURL(process.argv[1]).href : null
if (entrypointUrl && import.meta.url === entrypointUrl) {
  // Before anything logs, so every line this process writes — including the
  // bare console.* calls in cart.js/recgovSession.js — reaches Loki with a
  // `level` label. No-op on a TTY; see jsonConsole.js.
  installJsonConsole()
  installShutdownHandlers(startServer())
}
