import { errorAnswer, STATUS_FORBIDDEN, type HttpAnswer } from './httpAnswer';

export const CROSS_ORIGIN = 'cross_origin';

const SAFE_METHODS = new Set(['GET', 'HEAD', 'OPTIONS']);
const FETCH_SITE = 'sec-fetch-site';
const SAME_ORIGIN = 'same-origin';
const ORIGIN = 'origin';
const FORWARDED_HOST = 'x-forwarded-host';
const HOST = 'host';
const LIST_SEPARATOR = ',';

function hostOf(origin: string | null): string | null {
  if (origin === null) return null;
  try {
    return new URL(origin).host;
  } catch {
    return null;
  }
}

function servedHost(request: Request): string {
  const forwarded = request.headers.get(FORWARDED_HOST)?.split(LIST_SEPARATOR)[0]?.trim();
  return forwarded || request.headers.get(HOST) || new URL(request.url).host;
}

export function crossOriginRefusal(request: Request | undefined): HttpAnswer<never> | null {
  if (request === undefined || SAFE_METHODS.has(request.method)) return null;
  if (request.headers.get(FETCH_SITE) === SAME_ORIGIN) return null;
  if (hostOf(request.headers.get(ORIGIN)) === servedHost(request)) return null;
  return errorAnswer(STATUS_FORBIDDEN, CROSS_ORIGIN);
}
