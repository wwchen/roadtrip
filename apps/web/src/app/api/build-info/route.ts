import { config } from '@/server/config';

export const dynamic = 'force-dynamic';

export function GET(): Response {
  return Response.json(config.buildInfo);
}
