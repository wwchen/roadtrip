import { requireAppUser } from '@/server/auth/currentUser';
import { BOOKING_ROUTE_MAX_DURATION_S } from '@/server/config';
import { jsonBody } from '@/server/requestBody';
import { serviceResponse } from '@/server/serviceResponse';
import { liveRecgovDeps, remove, save } from '@/server/service/settings/recgovCredentialService';

export const dynamic = 'force-dynamic';
export const maxDuration = BOOKING_ROUTE_MAX_DURATION_S;

export async function PUT(request: Request): Promise<Response> {
  const user = await requireAppUser();
  if (user instanceof Response) return user;
  return serviceResponse(await save(user, await jsonBody(request), liveRecgovDeps()));
}

export async function DELETE(): Promise<Response> {
  const user = await requireAppUser();
  if (user instanceof Response) return user;
  return serviceResponse(await remove(user, liveRecgovDeps()));
}
