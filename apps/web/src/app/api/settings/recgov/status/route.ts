import { pendingChallenge, statusAnswer } from '@/server/mapper/http/recgovHttp';
import { recgovStatus } from '@/server/service/settings/recgovCredentialController';
import { recgovPorts } from '@/server/wiring/recgovPorts';
import { signedIn } from '@/server/wiring/signedIn';

export const dynamic = 'force-dynamic';
export const maxDuration = 120;

export const GET = signedIn(async ({ user, request }) => {
  const ports = recgovPorts();
  return statusAnswer(await recgovStatus(user, pendingChallenge(request, user, ports.challenges), ports));
});
