const FORWARDED_FOR = 'x-forwarded-for';
const LIST_SEPARATOR = ',';
const UNKNOWN_CALLER = 'unknown';

export const CALLER_THROTTLED = 'ip_throttled';

export function callerAddress(request: Request): string {
  const first = request.headers.get(FORWARDED_FOR)?.split(LIST_SEPARATOR)[0]?.trim();
  return first === undefined || first.length === 0 ? UNKNOWN_CALLER : first;
}
