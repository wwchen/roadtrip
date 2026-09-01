import {
  SCREENSHOT_DIAGNOSTIC_ROUTE_PREFIX,
  SCREENSHOT_ROUTE,
} from './recgovScreenshotRoutes.js'

export const OPENAPI_ROUTE = '/openapi.json'
export const SWAGGER_DOCS_ROUTE = '/docs'
export const COMPANION_SECURITY_SCHEME = 'companionToken'

const PROFILE_ID_SCHEMA = {
  type: 'string',
  description: 'Opaque per-user browser profile id. Required: there is no shared profile.',
  example: '42',
}

export const COMPANION_API_ROUTES = [
  {
    method: 'GET',
    path: '/',
    operationId: 'getOperatorPage',
    summary: 'Operator login and session page',
    responses: {
      200: htmlResponse('HTML operator page with Rec.gov login, refresh, logout, health, and live session screenshot controls'),
    },
  },
  {
    method: 'GET',
    path: '/health',
    operationId: 'getHealth',
    summary: 'Companion health and per-profile Rec.gov auth status',
    description:
      'Lock-free: answers while the profile is mid-login. Reachable without the ' +
      'shared-secret header from localhost only, which is what the Compose healthcheck uses.',
    parameters: [profileIdQueryParameter({ required: false })],
    responses: {
      200: jsonResponse('Companion health status', 'HealthResponse'),
      400: jsonResponse('profile_id is malformed', 'ErrorResponse'),
    },
  },
  {
    method: 'POST',
    path: '/login',
    operationId: 'postLogin',
    summary: 'Log one browser profile in to Rec.gov, in two phases when MFA is prompted',
    description:
      'Phase one posts profile_id + username + password. When Rec.gov prompts for a code the ' +
      'response is 401 `mfa_required` with a challenge_id that holds the profile lock until it is ' +
      'completed or expires; phase two posts profile_id + challenge_id + mfa_code.',
    requestBody: {
      required: true,
      content: {
        'application/x-www-form-urlencoded': {
          schema: { $ref: '#/components/schemas/LoginRequest' },
        },
        'application/json': {
          schema: { $ref: '#/components/schemas/LoginRequest' },
        },
      },
    },
    responses: {
      200: jsonResponse('Login succeeded', 'AuthResponse'),
      400: jsonResponse('Invalid request, missing profile_id, or an unknown/expired MFA challenge', 'ErrorResponse'),
      401: jsonResponse('Login failed, MFA is required and a challenge was opened, or the submitted code was rejected (mfa_invalid)', 'MfaChallengeResponse'),
      409: jsonResponse('The profile is already running work', 'ErrorResponse'),
      429: jsonResponse('The profile is in failed-login backoff', 'ErrorResponse'),
      503: jsonResponse('The concurrent-browser cap is reached and no profile is evictable', 'ErrorResponse'),
      500: jsonResponse('Login check failed unexpectedly', 'ErrorResponse'),
    },
  },
  {
    method: 'POST',
    path: '/logout',
    operationId: 'postLogout',
    summary: 'Click through the Rec.gov logout flow in one browser profile',
    parameters: [profileIdQueryParameter()],
    requestBody: profileRequestBody(),
    responses: {
      200: jsonResponse('Logout succeeded or the browser was already logged out', 'AuthResponse'),
      400: jsonResponse('Missing or malformed profile_id', 'ErrorResponse'),
      409: jsonResponse('The profile is already running work', 'ErrorResponse'),
      503: jsonResponse('The concurrent-browser cap is reached and no profile is evictable', 'ErrorResponse'),
      500: jsonResponse('Logout failed unexpectedly', 'AuthResponse'),
    },
  },
  {
    method: 'POST',
    path: '/refresh',
    operationId: 'postRefresh',
    summary: 'Force refresh one profile stored Rec.gov browser session',
    parameters: [profileIdQueryParameter()],
    requestBody: profileRequestBody(),
    responses: {
      200: jsonResponse('Refresh succeeded', 'AuthResponse'),
      400: jsonResponse('Missing or malformed profile_id', 'ErrorResponse'),
      401: jsonResponse('Refresh failed; interactive login required', 'AuthResponse'),
      409: jsonResponse('The profile is already running work', 'ErrorResponse'),
      503: jsonResponse('The concurrent-browser cap is reached and no profile is evictable', 'ErrorResponse'),
      500: jsonResponse('Refresh failed unexpectedly', 'ErrorResponse'),
    },
  },
  {
    method: 'POST',
    path: '/verify',
    operationId: 'postVerify',
    summary: 'Dry-run session check for one browser profile',
    description:
      'Loads the Rec.gov account page and reads GET /api/cart/shoppingcart from page context. ' +
      'It never clicks Reserve and never places a cart hold.',
    parameters: [profileIdQueryParameter()],
    requestBody: profileRequestBody(),
    responses: {
      200: jsonResponse('The profile session is live', 'VerifyResponse'),
      400: jsonResponse('Missing or malformed profile_id', 'ErrorResponse'),
      401: jsonResponse('The profile has no usable Rec.gov session', 'VerifyResponse'),
      409: jsonResponse('The profile is already running work', 'ErrorResponse'),
      503: jsonResponse('The concurrent-browser cap is reached and no profile is evictable', 'ErrorResponse'),
      500: jsonResponse('Verification failed unexpectedly', 'ErrorResponse'),
    },
  },
  {
    method: 'POST',
    path: '/atc',
    operationId: 'postAtc',
    summary: 'Run one-shot add-to-cart automation in one browser profile',
    requestBody: {
      required: true,
      content: {
        'application/json': {
          schema: { $ref: '#/components/schemas/AtcRequest' },
        },
      },
    },
    responses: {
      200: jsonResponse('ATC automation completed successfully', 'AtcResult'),
      400: jsonResponse('Missing or malformed profile_id', 'ErrorResponse'),
      409: jsonResponse('The profile is already running work', 'ErrorResponse'),
      422: jsonResponse('ATC request payload is invalid', 'ErrorResponse'),
      503: jsonResponse('The concurrent-browser cap is reached and no profile is evictable', 'ErrorResponse'),
      500: jsonResponse('ATC automation failed', 'AtcResult'),
    },
  },
  {
    method: 'GET',
    path: SCREENSHOT_ROUTE,
    operationId: 'getScreenshot',
    summary: 'Capture a live Recreation.gov page from one browser profile',
    parameters: [
      profileIdQueryParameter(),
      {
        name: 'path',
        in: 'query',
        description: 'Recreation.gov path to capture. Extra query parameters are forwarded to the target page.',
        schema: {
          type: 'string',
          example: '/camping/campgrounds/232447',
        },
      },
      {
        name: 'url',
        in: 'query',
        description: 'Absolute Recreation.gov URL to capture.',
        schema: {
          type: 'string',
          example: 'https://www.recreation.gov/',
        },
      },
    ],
    responses: {
      200: pngResponse('PNG screenshot of the top 1000px viewport'),
      400: jsonResponse('Target is not a Recreation.gov URL or path, or profile_id is missing', 'ErrorResponse'),
      409: jsonResponse('The profile is already running work', 'ErrorResponse'),
      503: jsonResponse('The concurrent-browser cap is reached and no profile is evictable', 'ErrorResponse'),
      500: jsonResponse('Screenshot capture failed', 'ErrorResponse'),
    },
  },
  {
    method: 'GET',
    path: `${SCREENSHOT_DIAGNOSTIC_ROUTE_PREFIX}/{filename}`,
    operationId: 'getDiagnosticScreenshot',
    summary: 'Read a stored login diagnostic screenshot',
    parameters: [
      {
        name: 'filename',
        in: 'path',
        required: true,
        schema: {
          type: 'string',
          example: 'recgov-login-2026-07-17T00-00-00-000Z-login_success.png',
        },
      },
    ],
    responses: {
      200: pngResponse('Stored diagnostic PNG'),
      400: jsonResponse('Diagnostic filename is invalid', 'ErrorResponse'),
      404: jsonResponse('Diagnostic screenshot was not found', 'ErrorResponse'),
    },
  },
  {
    method: 'GET',
    path: OPENAPI_ROUTE,
    operationId: 'getOpenApiJson',
    summary: 'OpenAPI JSON document',
    responses: {
      200: jsonResponse('OpenAPI document', 'OpenApiDocument'),
    },
  },
  {
    method: 'GET',
    path: SWAGGER_DOCS_ROUTE,
    operationId: 'getSwaggerDocs',
    summary: 'Swagger UI API docs',
    responses: {
      200: htmlResponse('Swagger UI page'),
    },
  },
]

export const COMPANION_API_SCHEMAS = {
  HealthResponse: {
    type: 'object',
    required: ['ok', 'busy', 'recgov_auth'],
    properties: {
      ok: { type: 'boolean' },
      busy: { type: 'boolean', description: 'Busy for the requested profile, or companion-wide when no profile_id is given.' },
      profile_id: PROFILE_ID_SCHEMA,
      recgov_auth: { $ref: '#/components/schemas/RecgovAuthStatus' },
      pool: { $ref: '#/components/schemas/ProfilePoolStatus' },
    },
  },
  ProfilePoolStatus: {
    type: 'object',
    properties: {
      resident: { type: 'integer', description: 'Profiles with a launched Chromium process.' },
      max_concurrent_browsers: { type: 'integer' },
      keep_warm: { type: 'array', items: PROFILE_ID_SCHEMA },
      keep_warm_overflow: {
        type: 'integer',
        description: 'Armed profiles beyond the cap. Armed profiles are never evicted; the overflow is reported instead.',
      },
      mfa_challenge_ttl_ms: { type: 'integer' },
      profiles: {
        type: 'array',
        items: {
          type: 'object',
          properties: {
            profile_id: PROFILE_ID_SCHEMA,
            resident: { type: 'boolean' },
            busy: { type: 'boolean' },
            operation: { type: 'string', nullable: true },
            keep_warm: { type: 'boolean' },
            mfa_pending: { type: 'boolean' },
            login_backoff_ms: { type: 'integer' },
          },
        },
      },
    },
  },
  RecgovAuthStatus: {
    type: 'object',
    properties: {
      login_status: { type: 'string', example: 'ok' },
      state: { type: 'string', example: 'ok' },
      logged_in: { type: 'boolean' },
      operation: { type: 'string', example: 'startup check' },
      checked_at: { type: 'string', format: 'date-time' },
      last_refresh_at: { type: 'string', format: 'date-time', nullable: true },
      last_refresh_expires_at: { type: 'string', format: 'date-time', nullable: true },
      next_refresh_at: { type: 'string', format: 'date-time', nullable: true },
      error: { type: 'string' },
      detail: { type: 'string' },
      corrective_action: { type: 'string' },
    },
  },
  LoginDiagnostic: {
    type: 'object',
    nullable: true,
    properties: {
      reason: { type: 'string', example: 'login_success' },
      detail: { type: 'string', nullable: true },
      captured_at: { type: 'string', format: 'date-time' },
      page_url: { type: 'string' },
      screenshot_url: { type: 'string' },
      screenshot_error: { type: 'string' },
    },
  },
  PlaywrightScreenshot: {
    type: 'object',
    properties: {
      label: { type: 'string', example: 'opened-booking-url' },
      captured_at: { type: 'string', format: 'date-time' },
      page_url: { type: 'string' },
      screenshot_url: { type: 'string' },
      screenshot_error: { type: 'string' },
    },
  },
  LoginRequest: {
    type: 'object',
    required: ['profile_id'],
    properties: {
      profile_id: PROFILE_ID_SCHEMA,
      username: {
        type: 'string',
        format: 'email',
        description: 'Recreation.gov username or email address',
        example: 'user@example.com',
      },
      password: { type: 'string', format: 'password' },
      mfa_code: {
        type: 'string',
        description: 'Current Recreation.gov MFA code, sent with challenge_id in phase two',
        example: '123456',
      },
      challenge_id: {
        type: 'string',
        description: 'Challenge id returned by a phase-one login that hit an MFA prompt',
      },
    },
  },
  ProfileRequest: {
    type: 'object',
    required: ['profile_id'],
    properties: {
      profile_id: PROFILE_ID_SCHEMA,
    },
  },
  MfaChallengeResponse: {
    type: 'object',
    required: ['ok', 'recgov_auth'],
    properties: {
      ok: { type: 'boolean', example: false },
      error: { type: 'string', example: 'mfa_required', description: 'mfa_required opens a challenge; mfa_invalid means the submitted code was rejected.' },
      challenge_id: { type: 'string' },
      expires_at: { type: 'string', format: 'date-time' },
      expires_in_seconds: { type: 'integer' },
      recgov_auth: { $ref: '#/components/schemas/RecgovAuthStatus' },
      diagnostics: { $ref: '#/components/schemas/LoginDiagnostic' },
    },
  },
  VerifyResponse: {
    type: 'object',
    required: ['ok', 'profile_id', 'verify'],
    properties: {
      ok: { type: 'boolean' },
      profile_id: PROFILE_ID_SCHEMA,
      verify: {
        type: 'object',
        properties: {
          ok: { type: 'boolean' },
          logged_in: { type: 'boolean' },
          account_url: { type: 'string' },
          cart_status: { type: 'integer', nullable: true },
          cart_reservation_count: { type: 'integer', nullable: true },
          token_expires_at: { type: 'string', nullable: true },
          checked_at: { type: 'string', format: 'date-time' },
          error: { type: 'string' },
          detail: { type: 'string' },
        },
      },
      recgov_auth: { $ref: '#/components/schemas/RecgovAuthStatus' },
    },
  },
  AuthResponse: {
    type: 'object',
    required: ['ok', 'recgov_auth', 'diagnostics'],
    properties: {
      ok: { type: 'boolean' },
      recgov_auth: { $ref: '#/components/schemas/RecgovAuthStatus' },
      diagnostics: { $ref: '#/components/schemas/LoginDiagnostic' },
    },
  },
  AtcRequest: {
    type: 'object',
    required: ['profile_id', 'start_date', 'end_date', 'campsite_id'],
    properties: {
      profile_id: PROFILE_ID_SCHEMA,
      start_date: { type: 'string', format: 'date' },
      end_date: { type: 'string', format: 'date' },
      campsite_id: idSchema('102524'),
    },
  },
  AtcResult: {
    type: 'object',
    required: ['ok', 'cart_added', 'logs', 'screenshots'],
    properties: {
      ok: { type: 'boolean' },
      cart_added: { type: 'boolean' },
      error: { type: 'string' },
      detail: { type: 'string' },
      booking_url: { type: 'string' },
      first_date: { type: 'string', format: 'date' },
      checkout_date: { type: 'string', format: 'date' },
      cart_check: { type: 'object', additionalProperties: true },
      logs: {
        type: 'array',
        items: { type: 'string' },
      },
      screenshots: {
        type: 'array',
        items: { $ref: '#/components/schemas/PlaywrightScreenshot' },
      },
    },
  },
  ErrorResponse: {
    type: 'object',
    required: ['ok', 'error'],
    properties: {
      ok: { type: 'boolean', example: false },
      error: { type: 'string' },
      detail: { type: 'string' },
    },
  },
  OpenApiDocument: {
    type: 'object',
    additionalProperties: true,
  },
}

export function matchCompanionRoute (method, pathname) {
  return COMPANION_API_ROUTES.find((route) =>
    route.method === method &&
    routePathMatches(route.path, pathname)
  ) || null
}

export function openApiPathsFromRoutes (routes = COMPANION_API_ROUTES) {
  return routes.reduce((paths, route) => {
    const {
      method,
      path,
      ...operation
    } = route
    paths[path] = {
      ...(paths[path] || {}),
      [method.toLowerCase()]: operation,
    }
    return paths
  }, {})
}

function routePathMatches (template, pathname) {
  if (!template.includes('{')) return template === pathname
  const templateParts = template.split('/').filter(Boolean)
  const pathParts = pathname.split('/').filter(Boolean)
  if (templateParts.length !== pathParts.length) return false
  return templateParts.every((part, index) => isPathParam(part) || part === pathParts[index])
}

function isPathParam (part) {
  return part.startsWith('{') && part.endsWith('}') && part.length > 2
}

function jsonResponse (description, schemaName) {
  return {
    description,
    content: {
      'application/json': {
        schema: { $ref: `#/components/schemas/${schemaName}` },
      },
    },
  }
}

function htmlResponse (description) {
  return {
    description,
    content: {
      'text/html': {
        schema: { type: 'string' },
      },
    },
  }
}

function pngResponse (description) {
  return {
    description,
    content: {
      'image/png': {
        schema: {
          type: 'string',
          format: 'binary',
        },
      },
    },
  }
}

function profileIdQueryParameter ({ required = true } = {}) {
  return {
    name: 'profile_id',
    in: 'query',
    required,
    description: 'Browser profile to act on. POST routes also accept it in the body.',
    schema: PROFILE_ID_SCHEMA,
  }
}

function profileRequestBody () {
  return {
    required: false,
    content: {
      'application/json': {
        schema: { $ref: '#/components/schemas/ProfileRequest' },
      },
      'application/x-www-form-urlencoded': {
        schema: { $ref: '#/components/schemas/ProfileRequest' },
      },
    },
  }
}

function idSchema (example) {
  return {
    oneOf: [
      { type: 'string' },
      { type: 'integer' },
    ],
    example,
  }
}
