import { addToCartAnswer, parseAddToCart } from '@/server/mapper/http/addToCartHttp';
import { addToCart } from '@/server/service/booking/addToCartController';
import { bookingPorts } from '@/server/wiring/bookingPorts';
import { signedIn } from '@/server/wiring/signedIn';

export const dynamic = 'force-dynamic';
export const maxDuration = 120;

export const POST = signedIn(async ({ user, readJson }) => {
  const command = parseAddToCart(await readJson());
  if (command.kind === 'invalid') return command.answer;
  return addToCartAnswer(await addToCart(user, command.value, bookingPorts()));
});
