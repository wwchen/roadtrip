import { mfaClearCookie, mfaSetCookie } from './auth/mfaChallengeCookie';
import type { ServiceAnswer } from './service/settings/recgovCredentialService';

const PRIVATE_NO_STORE = 'private, no-store';

export function serviceResponse<T>(answer: ServiceAnswer<T>): Response {
  const headers = new Headers({ 'cache-control': PRIVATE_NO_STORE });
  if (answer.cookie === 'clear') headers.append('set-cookie', mfaClearCookie());
  else if (answer.cookie !== undefined) headers.append('set-cookie', mfaSetCookie(answer.cookie.value, answer.cookie.maxAgeSeconds));
  return Response.json(answer.body, { status: answer.status, headers });
}
