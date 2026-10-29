import { isDatabaseReachable } from '@/server/service/healthService';

export const dynamic = 'force-dynamic';

const STATUS_OK = 200;
const STATUS_UNAVAILABLE = 503;

export async function GET(): Promise<Response> {
  const reachable = await isDatabaseReachable();
  return Response.json(
    { status: reachable ? 'ok' : 'unavailable' },
    { status: reachable ? STATUS_OK : STATUS_UNAVAILABLE },
  );
}
