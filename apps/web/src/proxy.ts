import { NextResponse, type NextRequest } from 'next/server';
import { ERROR_LINK, ERROR_PARAM, ERROR_RETRY } from '@/server/auth/authActions';
import { SIGN_IN_PATH } from '@/server/auth/authPaths';
import { NEON_AUTH_COOKIE_PREFIX, neonAuth } from '@/server/auth/neonAuth';
import { safeReturnTo, withReturnTo } from '@/server/auth/returnTo';

const VERIFIER_PARAM = 'neon_auth_session_verifier';
const CHALLENGE_COOKIES = [`${NEON_AUTH_COOKIE_PREFIX}session_challenge`, `${NEON_AUTH_COOKIE_PREFIX}session_challange`];

function signInUrl(request: NextRequest, error: string): string {
  const landing = new URL(request.nextUrl);
  landing.searchParams.delete(VERIFIER_PARAM);
  const returnTo = safeReturnTo(`${landing.pathname}${landing.search}`);
  return `${withReturnTo(SIGN_IN_PATH, returnTo)}&${ERROR_PARAM}=${error}`;
}

export async function proxy(request: NextRequest): Promise<Response> {
  const auth = neonAuth();
  if (auth === null) return NextResponse.next();
  if (!CHALLENGE_COOKIES.some((name) => request.cookies.has(name))) {
    return NextResponse.redirect(new URL(signInUrl(request, ERROR_LINK), request.url));
  }
  return auth.middleware({ loginUrl: signInUrl(request, ERROR_RETRY) })(request);
}

export const config = {
  matcher: [{ source: '/:path*', has: [{ type: 'query', key: 'neon_auth_session_verifier' }] }],
};
