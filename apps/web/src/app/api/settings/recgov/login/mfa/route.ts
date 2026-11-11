import { mfaCookieFrom } from '@/server/auth/mfaChallengeCookie';
import { requireAppUser } from '@/server/auth/currentUser';
import { BOOKING_ROUTE_MAX_DURATION_S } from '@/server/config';
import { jsonBody } from '@/server/requestBody';
import { serviceResponse } from '@/server/serviceResponse';
import { completeMfa, liveRecgovDeps } from '@/server/service/settings/recgovCredentialService';

export const dynamic = 'force-dynamic';
export const maxDuration = BOOKING_ROUTE_MAX_DURATION_S;

export async function POST(request: Request): Promise<Response> {
  const user = await requireAppUser();
  if (user instanceof Response) return user;
  return serviceResponse(await completeMfa(user, await jsonBody(request), mfaCookieFrom(request), liveRecgovDeps()));
}
