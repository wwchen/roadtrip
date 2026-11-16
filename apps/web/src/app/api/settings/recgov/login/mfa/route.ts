import { loginAnswer, parseMfaCode, pendingChallenge } from '@/server/mapper/http/recgovHttp';
import { submitMfa } from '@/server/service/settings/recgovCredentialController';
import { recgovPorts } from '@/server/wiring/recgovPorts';
import { signedIn } from '@/server/wiring/signedIn';

export const dynamic = 'force-dynamic';
export const maxDuration = 120;

export const POST = signedIn(async ({ user, readJson, request }) => {
  const ports = recgovPorts();
  const code = parseMfaCode(await readJson());
  if (code.kind === 'invalid') return code.answer;
  return loginAnswer(await submitMfa(user, code.value, pendingChallenge(request, user, ports.challenges), ports), ports.challenges);
});
