import { NextResponse, type NextRequest } from 'next/server';
import { neonAuth } from '@/server/auth/neonAuth';

const SIGN_IN_PATH = '/auth/sign-in';

export async function proxy(request: NextRequest): Promise<Response> {
  const auth = neonAuth();
  if (auth === null) return NextResponse.next();
  return auth.middleware({ loginUrl: SIGN_IN_PATH })(request);
}

export const config = {
  matcher: [{ source: '/:path*', has: [{ type: 'query', key: 'neon_auth_session_verifier' }] }],
};
