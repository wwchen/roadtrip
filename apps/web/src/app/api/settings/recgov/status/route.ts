import { mfaCookieFrom } from '@/server/auth/mfaChallengeCookie';
import { requireAppUser } from '@/server/auth/currentUser';
import { serviceResponse } from '@/server/serviceResponse';
import { liveRecgovDeps, status } from '@/server/service/settings/recgovCredentialService';

export const dynamic = 'force-dynamic';
export const maxDuration = 120;

export async function GET(request: Request): Promise<Response> {
  const user = await requireAppUser();
  if (user instanceof Response) return user;
  return serviceResponse(await status(user, mfaCookieFrom(request), liveRecgovDeps()));
}
