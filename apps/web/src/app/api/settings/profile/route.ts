import { apiError } from '@/server/contractIds';
import { requireAppUser } from '@/server/auth/currentUser';
import { jsonBody } from '@/server/requestBody';
import { updateCurrentProfile } from '@/server/service/settings/settingsService';

export const dynamic = 'force-dynamic';

const STATUS_BAD_REQUEST = 400;
const PRIVATE_NO_STORE = 'private, no-store';

export async function PUT(request: Request): Promise<Response> {
  const user = await requireAppUser();
  if (user instanceof Response) return user;
  const result = await updateCurrentProfile(user, await jsonBody(request));
  if (!result.ok) return apiError(STATUS_BAD_REQUEST, result.error, result.detail);
  return Response.json(result.settings, { headers: { 'cache-control': PRIVATE_NO_STORE } });
}
