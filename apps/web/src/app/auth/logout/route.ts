import { authPort } from '@/server/auth/neonAuth';
import { UNKNOWN_FAILURE } from '@/server/auth/authPort';
import { HOME_PATH } from '@/server/auth/returnTo';

export const dynamic = 'force-dynamic';

const STATUS_FOUND = 302;
const AUTH_LOG = 'auth:';

function codeOf(error: unknown): string {
  const code = (error as { code?: unknown } | null)?.code;
  return typeof code === 'string' ? code : UNKNOWN_FAILURE;
}

export async function GET(): Promise<Response> {
  try {
    const result = await authPort()?.signOut();
    if (result !== undefined && !result.ok) console.warn(AUTH_LOG, result.code);
  } catch (error) {
    console.warn(AUTH_LOG, codeOf(error));
  }
  return new Response(null, { status: STATUS_FOUND, headers: { location: HOME_PATH } });
}
