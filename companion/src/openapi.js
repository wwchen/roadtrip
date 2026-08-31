import {
  COMPANION_API_SCHEMAS,
  COMPANION_API_ROUTES,
  COMPANION_SECURITY_SCHEME,
  OPENAPI_ROUTE,
  SWAGGER_DOCS_ROUTE,
  openApiPathsFromRoutes,
} from './apiContract.js'
import { COMPANION_API_TOKEN_HEADER } from './server/apiToken.js'

export {
  OPENAPI_ROUTE,
  SWAGGER_DOCS_ROUTE,
} from './apiContract.js'

export const COMPANION_OPENAPI_SPEC = {
  openapi: '3.0.3',
  info: {
    title: 'Campsite Companion API',
    version: '0.1.0',
    description:
      'Host-local companion API for Recreation.gov auth inspection and add-to-cart execution. ' +
      'Every route is scoped to one browser profile via profile_id and requires the shared-secret ' +
      'header; only GET /health from localhost is exempt.',
  },
  security: [{ [COMPANION_SECURITY_SCHEME]: [] }],
  servers: [
    {
      url: '/',
      description: 'Current companion server',
    },
  ],
  paths: openApiPathsFromRoutes(COMPANION_API_ROUTES),
  components: {
    schemas: COMPANION_API_SCHEMAS,
    securitySchemes: {
      [COMPANION_SECURITY_SCHEME]: {
        type: 'apiKey',
        in: 'header',
        name: COMPANION_API_TOKEN_HEADER,
        description: 'COMPANION_API_TOKEN, the shared secret the backend and the companion both hold.',
      },
    },
  },
}
