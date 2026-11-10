import { forwardToKotlin } from '@/server/client/kotlinPassthrough';

export const dynamic = 'force-dynamic';

export async function GET(request: Request): Promise<Response> {
  return forwardToKotlin(request);
}
