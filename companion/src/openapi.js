import {
  COMPANION_API_SCHEMAS,
  COMPANION_API_ROUTES,
  OPENAPI_ROUTE,
  SWAGGER_DOCS_ROUTE,
  openApiPathsFromRoutes,
} from './apiContract.js'

export {
  OPENAPI_ROUTE,
  SWAGGER_DOCS_ROUTE,
} from './apiContract.js'

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
  paths: openApiPathsFromRoutes(COMPANION_API_ROUTES),
  components: {
    schemas: COMPANION_API_SCHEMAS,
  },
}
