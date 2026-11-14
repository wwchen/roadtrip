import { parseCredentials, removeAnswer, saveAnswer } from '@/server/mapper/http/recgovHttp';
import { removeCredentials, saveCredentials } from '@/server/service/settings/recgovCredentialController';
import { recgovPorts } from '@/server/wiring/recgovPorts';
import { signedIn } from '@/server/wiring/signedIn';

export const dynamic = 'force-dynamic';
export const maxDuration = 120;

export const PUT = signedIn(async ({ user, readJson }) => {
  const command = parseCredentials(await readJson());
  if (command.kind === 'invalid') return command.answer;
  return saveAnswer(await saveCredentials(user, command.value, recgovPorts()));
});

export const DELETE = signedIn(async ({ user }) => removeAnswer(await removeCredentials(user, recgovPorts())));
