import {
  SCREENSHOT_DIAGNOSTIC_ROUTE_PREFIX,
  SCREENSHOT_ROUTE,
} from './recgovScreenshotRoutes.js'

export const OPENAPI_ROUTE = '/openapi.json'
export const SWAGGER_DOCS_ROUTE = '/docs'

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
    summary: 'Companion health and Rec.gov auth status',
    responses: {
      200: jsonResponse('Companion health status', 'HealthResponse'),
    },
  },
  {
    method: 'POST',
    path: '/login',
    operationId: 'postLogin',
    summary: 'Submit Rec.gov credentials for the companion browser session',
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
      400: jsonResponse('Invalid or incomplete login request', 'ErrorResponse'),
      401: jsonResponse('Login failed or Rec.gov rejected the session', 'AuthResponse'),
      409: jsonResponse('Companion is already running work', 'ErrorResponse'),
      500: jsonResponse('Login check failed unexpectedly', 'ErrorResponse'),
    },
  },
  {
    method: 'POST',
    path: '/logout',
    operationId: 'postLogout',
    summary: 'Click through the Rec.gov logout flow in the companion browser',
    responses: {
      200: jsonResponse('Logout succeeded or the browser was already logged out', 'AuthResponse'),
      409: jsonResponse('Companion is already running work', 'ErrorResponse'),
      500: jsonResponse('Logout failed unexpectedly', 'AuthResponse'),
    },
  },
  {
    method: 'POST',
    path: '/refresh',
    operationId: 'postRefresh',
    summary: 'Force refresh the stored Rec.gov browser session',
    responses: {
      200: jsonResponse('Refresh succeeded', 'AuthResponse'),
      401: jsonResponse('Refresh failed; operator login required', 'AuthResponse'),
      409: jsonResponse('Companion is already running work', 'ErrorResponse'),
      500: jsonResponse('Refresh failed unexpectedly', 'ErrorResponse'),
    },
  },
  {
    method: 'POST',
    path: '/atc',
    operationId: 'postAtc',
    summary: 'Run one-shot add-to-cart automation',
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
      409: jsonResponse('Companion is already running an ATC request', 'ErrorResponse'),
      422: jsonResponse('ATC request payload is invalid', 'ErrorResponse'),
      500: jsonResponse('ATC automation failed', 'AtcResult'),
    },
  },
  {
    method: 'GET',
    path: SCREENSHOT_ROUTE,
    operationId: 'getScreenshot',
    summary: 'Capture a live Recreation.gov page from the companion browser',
    parameters: [
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
      400: jsonResponse('Target is not a Recreation.gov URL or path', 'ErrorResponse'),
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
      busy: { type: 'boolean' },
      recgov_auth: { $ref: '#/components/schemas/RecgovAuthStatus' },
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
  LoginRequest: {
    type: 'object',
    required: ['username', 'password'],
    properties: {
      username: {
        type: 'string',
        format: 'email',
        description: 'Recreation.gov username or email address',
        example: 'user@example.com',
      },
      password: { type: 'string', format: 'password' },
      mfa_code: {
        type: 'string',
        description: 'Current Recreation.gov MFA code when prompted',
        example: '123456',
      },
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
    required: ['start_date', 'end_date', 'vendor', 'booking_url', 'campground_id', 'campsite_id'],
    properties: {
      vendor: { type: 'string', enum: ['recgov'], example: 'recgov' },
      start_date: { type: 'string', format: 'date' },
      end_date: { type: 'string', format: 'date' },
      booking_url: {
        type: 'string',
        example: 'https://www.recreation.gov/camping/campsites/102524?startDate=2026-07-19&endDate=2026-07-20',
      },
      campground_id: idSchema('232447'),
      campsite_id: idSchema('102524'),
    },
  },
  AtcResult: {
    type: 'object',
    required: ['ok', 'cart_added'],
    properties: {
      ok: { type: 'boolean' },
      cart_added: { type: 'boolean' },
      error: { type: 'string' },
      detail: { type: 'string' },
      booking_url: { type: 'string' },
      campsite_site: { type: 'string' },
      first_date: { type: 'string', format: 'date' },
      checkout_date: { type: 'string', format: 'date' },
      cart_check: { type: 'object', additionalProperties: true },
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

function idSchema (example) {
  return {
    oneOf: [
      { type: 'string' },
      { type: 'integer' },
    ],
    example,
  }
}
