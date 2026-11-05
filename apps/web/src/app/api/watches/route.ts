import { apiError } from '@/server/contractIds';

export const dynamic = 'force-dynamic';

const STATUS_UNAUTHORIZED = 401;
const UNAUTHENTICATED = 'unauthenticated';

export function GET(): Response {
  return apiError(STATUS_UNAUTHORIZED, UNAUTHENTICATED);
}
