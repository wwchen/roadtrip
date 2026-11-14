import { requireAppUser } from '@/server/auth/currentUser';
import { currentSettings } from '@/server/service/settings/settingsService';

export const dynamic = 'force-dynamic';

const PRIVATE_NO_STORE = 'private, no-store';

export async function GET(): Promise<Response> {
  const user = await requireAppUser();
  if (user instanceof Response) return user;
  return Response.json(await currentSettings(user), { headers: { 'cache-control': PRIVATE_NO_STORE } });
}
