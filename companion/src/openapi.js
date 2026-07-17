export const OPENAPI_ROUTE = '/openapi.json'
export const SWAGGER_DOCS_ROUTE = '/docs'

export const COMPANION_OPENAPI_SPEC = {
  openapi: '3.0.3',
  info: {
    title: 'Campsite Companion API',
    version: '0.1.0',
    description: 'Host-local companion API for Recreation.gov auth inspection and add-to-cart execution.',
  },
  servers: [
    {
      url: '/',
      description: 'Current companion server',
    },
  ],
  paths: {
    '/health': {
      get: {
        summary: 'Companion health and Rec.gov auth status',
        operationId: 'getHealth',
        responses: {
          200: jsonResponse('Companion health status', 'HealthResponse'),
        },
      },
    },
    '/login': {
      get: {
        summary: 'Operator login page',
        operationId: 'getLoginPage',
        responses: {
          200: htmlResponse('HTML login page with live Rec.gov session screenshot'),
        },
      },
      post: {
        summary: 'Submit Rec.gov credentials for the companion browser session',
        operationId: 'postLogin',
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
    },
    '/refresh': {
      get: {
        summary: 'Operator refresh page',
        operationId: 'getRefreshPage',
        responses: {
          200: htmlResponse('HTML refresh page'),
        },
      },
      post: {
        summary: 'Force refresh the stored Rec.gov browser session',
        operationId: 'postRefresh',
        responses: {
          200: jsonResponse('Refresh succeeded', 'AuthResponse'),
          401: jsonResponse('Refresh failed; operator login required', 'AuthResponse'),
          409: jsonResponse('Companion is already running work', 'ErrorResponse'),
          500: jsonResponse('Refresh failed unexpectedly', 'ErrorResponse'),
        },
      },
    },
    '/recgov/atc': {
      post: {
        summary: 'Run one-shot Rec.gov add-to-cart automation',
        operationId: 'postRecgovAtc',
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
    },
    '/screenshot': {
      get: {
        summary: 'Capture a live Recreation.gov page from the companion browser',
        operationId: 'getScreenshot',
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
    },
    '/diagnostics/{filename}': {
      get: {
        summary: 'Read a stored login diagnostic screenshot',
        operationId: 'getDiagnosticScreenshot',
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
    },
    [OPENAPI_ROUTE]: {
      get: {
        summary: 'OpenAPI JSON document',
        operationId: 'getOpenApiJson',
        responses: {
          200: jsonResponse('OpenAPI document', 'OpenApiDocument'),
        },
      },
    },
    [SWAGGER_DOCS_ROUTE]: {
      get: {
        summary: 'Swagger UI API docs',
        operationId: 'getSwaggerDocs',
        responses: {
          200: htmlResponse('Swagger UI page'),
        },
      },
    },
  },
  components: {
    schemas: {
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
          diagnostic: { $ref: '#/components/schemas/LoginDiagnostic' },
          last_login_diagnostic: { $ref: '#/components/schemas/LoginDiagnostic' },
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
          screenshot_path: { type: 'string' },
          screenshot_url: { type: 'string' },
          screenshot_error: { type: 'string' },
        },
      },
      LoginRequest: {
        type: 'object',
        required: ['username', 'password'],
        properties: {
          username: { type: 'string', format: 'email' },
          email: { type: 'string', format: 'email' },
          password: { type: 'string', format: 'password' },
          mfa_code: { type: 'string', description: 'Current Rec.gov MFA code when prompted' },
          mfaCode: { type: 'string', description: 'JSON alias for mfa_code' },
        },
      },
      AuthResponse: {
        type: 'object',
        required: ['ok', 'recgov_auth'],
        properties: {
          ok: { type: 'boolean' },
          recgov_auth: { $ref: '#/components/schemas/RecgovAuthStatus' },
        },
      },
      AtcOpening: {
        type: 'object',
        required: ['label', 'date', 'vendor', 'vendor_id', 'booking_url'],
        properties: {
          label: { type: 'string', example: '008' },
          date: { type: 'string', format: 'date' },
          vendor: { type: 'string', example: 'recgov' },
          campsite_id: { type: 'integer' },
          vendor_id: { type: 'string', example: '102524' },
          loop: { type: 'string' },
          site_type: { type: 'string' },
          campground_id: { type: 'integer' },
          campground: { type: 'string' },
          booking_url: {
            type: 'string',
            example: 'https://www.recreation.gov/camping/campsites/102524?startDate=2026-07-19&endDate=2026-07-20',
          },
        },
      },
      AtcRequest: {
        type: 'object',
        required: ['watch_id', 'vendor', 'payload_version', 'start_date', 'end_date', 'openings'],
        properties: {
          watch_id: { type: 'integer', example: 14 },
          vendor: { type: 'string', example: 'recgov' },
          payload_version: { type: 'string', example: 'atc.recgov.v1' },
          start_date: { type: 'string', format: 'date' },
          end_date: { type: 'string', format: 'date' },
          openings: {
            type: 'array',
            items: { $ref: '#/components/schemas/AtcOpening' },
          },
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
    },
  },
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
