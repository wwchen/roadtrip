import { loginAnswer } from '@/server/mapper/http/recgovHttp';
import { signIn } from '@/server/service/settings/recgovCredentialController';
import { recgovPorts } from '@/server/wiring/recgovPorts';
import { signedIn } from '@/server/wiring/signedIn';

export const dynamic = 'force-dynamic';
export const maxDuration = 200;

export const POST = signedIn(async ({ user }) => {
  const ports = recgovPorts();
  return loginAnswer(await signIn(user, ports), ports.challenges);
});
