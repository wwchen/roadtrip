import { appDb } from '@/server/appDb';
import { authPort } from '@/server/auth/neonAuth';
import { config } from '@/server/config';
import { linkedAppUser } from '@/server/repo/appUserRepo';
import { meFor } from '@/server/service/auth/meService';

export const dynamic = 'force-dynamic';

const NO_ADMINS: ReadonlySet<string> = new Set();

export async function GET(): Promise<Response> {
  const me = await meFor(
    authPort(),
    (claims) => linkedAppUser(appDb(), claims),
    config.signIn?.adminEmails ?? NO_ADMINS,
  );
  return Response.json(me);
}
