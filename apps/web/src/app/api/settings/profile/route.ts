import { parseProfilePatch, settingsAnswer } from '@/server/mapper/http/settingsHttp';
import { updateProfile } from '@/server/service/settings/settingsController';
import { settingsPorts } from '@/server/wiring/settingsPorts';
import { signedIn } from '@/server/wiring/signedIn';

export const dynamic = 'force-dynamic';

export const PUT = signedIn(async ({ user, readJson }) => {
  const patch = parseProfilePatch(await readJson());
  if (patch.kind === 'invalid') return patch.answer;
  return settingsAnswer(await updateProfile(user, patch.value, settingsPorts()));
});
