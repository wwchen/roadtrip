import { requireAppUser } from '@/server/auth/currentUser';
import { serviceResponse } from '@/server/serviceResponse';
import { liveRecgovDeps, login } from '@/server/service/settings/recgovCredentialService';

export const dynamic = 'force-dynamic';
export const maxDuration = 200;

export async function POST(): Promise<Response> {
  const user = await requireAppUser();
  if (user instanceof Response) return user;
  return serviceResponse(await login(user, liveRecgovDeps()));
}
