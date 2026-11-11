import { requireAppUser } from '@/server/auth/currentUser';
import { BOOKING_ROUTE_MAX_DURATION_S } from '@/server/config';
import { serviceResponse } from '@/server/serviceResponse';
import { liveRecgovDeps, verify } from '@/server/service/settings/recgovCredentialService';

export const dynamic = 'force-dynamic';
export const maxDuration = BOOKING_ROUTE_MAX_DURATION_S;

export async function POST(): Promise<Response> {
  const user = await requireAppUser();
  if (user instanceof Response) return user;
  return serviceResponse(await verify(user, liveRecgovDeps()));
}
