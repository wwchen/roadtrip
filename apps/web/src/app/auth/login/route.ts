import { SIGN_IN_PATH } from '@/server/auth/authPaths';
import { RETURN_TO_PARAM, safeReturnTo, withReturnTo } from '@/server/auth/returnTo';

export const dynamic = 'force-dynamic';

const STATUS_FOUND = 302;

export function GET(request: Request): Response {
  const returnTo = safeReturnTo(new URL(request.url).searchParams.get(RETURN_TO_PARAM));
  return new Response(null, { status: STATUS_FOUND, headers: { location: withReturnTo(SIGN_IN_PATH, returnTo) } });
}
