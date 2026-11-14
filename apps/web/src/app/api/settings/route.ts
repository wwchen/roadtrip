import { settingsAnswer } from '@/server/mapper/http/settingsHttp';
import { settingsView } from '@/server/service/settings/settingsController';
import { settingsPorts } from '@/server/wiring/settingsPorts';
import { signedIn } from '@/server/wiring/signedIn';

export const dynamic = 'force-dynamic';

export const GET = signedIn(async ({ user }) => settingsAnswer(await settingsView(user, settingsPorts())));
