import type { MeResponseDto } from '@/server/contract';

export const dynamic = 'force-dynamic';

const ANONYMOUS_WITHOUT_IDENTITY_PROVIDER: MeResponseDto = {
  authenticated: false,
  auth_enabled: false,
  auth_embedded: false,
};

export function GET(): Response {
  return Response.json(ANONYMOUS_WITHOUT_IDENTITY_PROVIDER);
}
