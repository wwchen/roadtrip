import { config } from '../config';

export const KOTLIN_READ_PATH = '^/api/(availability/(pollers(/summary|/[0-9]+/runs)?|runs|changes(/summary)?)|docs(/.*)?)$';
export const KOTLIN_FORCE_PATH = '^/api/availability/pollers/[0-9]+/force$';
export const KOTLIN_PASSTHROUGH_TIMEOUT_MS = 10_000;

const ACCESS_CLIENT_ID_HEADER = 'cf-access-client-id';
const ACCESS_CLIENT_SECRET_HEADER = 'cf-access-client-secret';
const FORWARDED_REQUEST_HEADERS = ['accept', 'content-type'] as const;
const FORWARDED_RESPONSE_HEADERS = ['content-type', 'cache-control', 'retry-after'] as const;
const FIRST_REDIRECT_STATUS = 300;
const FIRST_CLIENT_ERROR_STATUS = 400;
const STATUS_NOT_FOUND = 404;
const STATUS_SERVICE_UNAVAILABLE = 503;
const UNAVAILABLE = 'kotlin_unavailable';
const PASSTHROUGH_LOG = 'kotlin passthrough';
const NOT_FOUND = 'not_found';
const LOCATION_HEADER = 'location';
const PATH_PREFIX = '/';
const PROTOCOL_RELATIVE_PREFIX = '//';
const GET = 'GET';
const POST = 'POST';

const readRule = new RegExp(KOTLIN_READ_PATH);
const forceRule = new RegExp(KOTLIN_FORCE_PATH);

export function isPassthrough(method: string, pathname: string): boolean {
  if (method === GET) return readRule.test(pathname);
  if (method === POST) return forceRule.test(pathname);
  return false;
}

function failure(status: number, error: string): Response {
  return Response.json({ error }, { status });
}

export async function forwardToKotlin(request: Request): Promise<Response> {
  const url = new URL(request.url);
  if (!isPassthrough(request.method, url.pathname)) return failure(STATUS_NOT_FOUND, NOT_FOUND);
  const egress = config.availabilityEgress;
  if (egress === null) return failure(STATUS_SERVICE_UNAVAILABLE, UNAVAILABLE);
  const headers = new Headers({
    [ACCESS_CLIENT_ID_HEADER]: egress.egressAccess.clientId,
    [ACCESS_CLIENT_SECRET_HEADER]: egress.egressAccess.clientSecret,
  });
  for (const name of FORWARDED_REQUEST_HEADERS) {
    const value = request.headers.get(name);
    if (value !== null) headers.set(name, value);
  }
  let upstream: Response;
  try {
    upstream = await fetch(new URL(`${url.pathname}${url.search}`, egress.egressUrl), {
      method: request.method,
      headers,
      body: request.method === POST ? await request.arrayBuffer() : undefined,
      signal: AbortSignal.timeout(KOTLIN_PASSTHROUGH_TIMEOUT_MS),
      cache: 'no-store',
      redirect: 'manual',
    });
  } catch (error) {
    console.warn(PASSTHROUGH_LOG, JSON.stringify({ path: url.pathname, error: String(error) }));
    return failure(STATUS_SERVICE_UNAVAILABLE, UNAVAILABLE);
  }
  if (upstream.status >= FIRST_REDIRECT_STATUS && upstream.status < FIRST_CLIENT_ERROR_STATUS) {
    const location = upstream.headers.get(LOCATION_HEADER);
    if (location !== null && location.startsWith(PATH_PREFIX) && !location.startsWith(PROTOCOL_RELATIVE_PREFIX)) {
      const target = new URL(location, url);
      if (isPassthrough(GET, target.pathname)) {
        return new Response(null, { status: upstream.status, headers: { [LOCATION_HEADER]: `${target.pathname}${target.search}` } });
      }
    }
    console.warn(PASSTHROUGH_LOG, JSON.stringify({ path: url.pathname, status: upstream.status, problem: 'redirected' }));
    return failure(STATUS_SERVICE_UNAVAILABLE, UNAVAILABLE);
  }
  const responseHeaders = new Headers();
  for (const name of FORWARDED_RESPONSE_HEADERS) {
    const value = upstream.headers.get(name);
    if (value !== null) responseHeaders.set(name, value);
  }
  return new Response(upstream.body, { status: upstream.status, headers: responseHeaders });
}
