import { authPort } from '@/server/auth/neonAuth';
import { HOME_PATH } from '@/server/auth/returnTo';

export const dynamic = 'force-dynamic';

const STATUS_FOUND = 302;

export async function GET(): Promise<Response> {
  await authPort()?.signOut();
  return new Response(null, { status: STATUS_FOUND, headers: { location: HOME_PATH } });
}
