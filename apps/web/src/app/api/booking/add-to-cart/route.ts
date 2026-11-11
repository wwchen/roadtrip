import { requireAppUser } from '@/server/auth/currentUser';
import { BOOKING_ROUTE_MAX_DURATION_S } from '@/server/config';
import { jsonBody } from '@/server/requestBody';
import { addToCart, liveAddToCartDeps } from '@/server/service/booking/addToCartService';

export const dynamic = 'force-dynamic';
export const maxDuration = BOOKING_ROUTE_MAX_DURATION_S;

const PRIVATE_NO_STORE = 'private, no-store';

export async function POST(request: Request): Promise<Response> {
  const user = await requireAppUser();
  if (user instanceof Response) return user;
  const answer = await addToCart(user, await jsonBody(request), liveAddToCartDeps());
  return Response.json(answer.body, { status: answer.status, headers: { 'cache-control': PRIVATE_NO_STORE } });
}
