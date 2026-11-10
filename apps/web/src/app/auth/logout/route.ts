import { cookies } from 'next/headers';
import { UNKNOWN_FAILURE } from '@/server/auth/authPort';
import { NEON_AUTH_COOKIE_PREFIX, authPort } from '@/server/auth/neonAuth';
import { HOME_PATH } from '@/server/auth/returnTo';

export const dynamic = 'force-dynamic';

const STATUS_FOUND = 302;
const AUTH_LOG = 'auth:';
const EXPIRED = { maxAge: 0, path: HOME_PATH, secure: true, httpOnly: true, sameSite: 'lax' } as const;
const EMPTY_VALUE = '';

function codeOf(error: unknown): string {
  const code = (error as { code?: unknown } | null)?.code;
  return typeof code === 'string' ? code : UNKNOWN_FAILURE;
}

async function expireNeonAuthCookies(): Promise<void> {
  const jar = await cookies();
  for (const { name } of jar.getAll()) {
    if (name.startsWith(NEON_AUTH_COOKIE_PREFIX)) jar.set(name, EMPTY_VALUE, EXPIRED);
  }
}

export async function GET(): Promise<Response> {
  let failure: string | null = null;
  try {
    const result = await authPort()?.signOut();
    if (result !== undefined && !result.ok) failure = result.code;
  } catch (error) {
    failure = codeOf(error);
  }
  if (failure !== null) {
    console.warn(AUTH_LOG, failure);
    await expireNeonAuthCookies();
  }
  return new Response(null, { status: STATUS_FOUND, headers: { location: HOME_PATH } });
}
