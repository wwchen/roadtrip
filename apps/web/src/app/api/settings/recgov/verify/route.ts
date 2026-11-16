import { verifyAnswer } from '@/server/mapper/http/recgovHttp';
import { verifySession } from '@/server/service/settings/recgovCredentialController';
import { recgovPorts } from '@/server/wiring/recgovPorts';
import { signedIn } from '@/server/wiring/signedIn';

export const dynamic = 'force-dynamic';
export const maxDuration = 120;

export const POST = signedIn(async ({ user }) => verifyAnswer(await verifySession(user, recgovPorts())));
