import { currentMe } from '@/server/service/auth/meService';

export const dynamic = 'force-dynamic';

export async function GET(): Promise<Response> {
  return Response.json(await currentMe());
}
