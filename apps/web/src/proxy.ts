import { NextResponse, type NextRequest } from 'next/server';
import { SIGN_IN_PATH } from '@/server/auth/authPaths';
import { neonAuth } from '@/server/auth/neonAuth';

export async function proxy(request: NextRequest): Promise<Response> {
  const auth = neonAuth();
  if (auth === null) return NextResponse.next();
  return auth.middleware({ loginUrl: SIGN_IN_PATH })(request);
}

export const config = {
  matcher: [{ source: '/:path*', has: [{ type: 'query', key: 'neon_auth_session_verifier' }] }],
};
