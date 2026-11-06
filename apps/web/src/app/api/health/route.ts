import { config } from '@/server/config';
import { isAuthorizedForDatabaseCheck } from '@/server/healthAuth';
import { isDatabaseReachable } from '@/server/service/healthService';

export const dynamic = 'force-dynamic';

const AUTHORIZATION_HEADER = 'Authorization';
const CHALLENGE_HEADER = 'WWW-Authenticate';
const BEARER_CHALLENGE = 'Bearer';
const STATUS_OK = 200;
const STATUS_UNAUTHORIZED = 401;
const STATUS_UNAVAILABLE = 503;

export async function GET(request: Request): Promise<Response> {
  const presented = request.headers.get(AUTHORIZATION_HEADER);
  if (presented === null) return Response.json({ status: 'ok' }, { status: STATUS_OK });

  if (!isAuthorizedForDatabaseCheck(presented, config.healthDatabaseToken)) {
    return Response.json(
      { status: 'unauthorized' },
      { status: STATUS_UNAUTHORIZED, headers: { [CHALLENGE_HEADER]: BEARER_CHALLENGE } },
    );
  }

  const reachable = await isDatabaseReachable();
  return Response.json(
    { status: reachable ? 'ok' : 'unavailable' },
    { status: reachable ? STATUS_OK : STATUS_UNAVAILABLE },
  );
}
