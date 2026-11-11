import { appDb } from '@/server/appDb';
import { apiError } from '@/server/contractIds';
import { ConfigError } from '@/server/config';
import { linkedAppUser, type AppUserRow, type IdentityClaims } from '@/server/repo/appUserRepo';
import type { AuthPort } from './authPort';
import { authPort } from './neonAuth';

const STATUS_UNAUTHORIZED = 401;
const UNAUTHENTICATED = 'unauthenticated';
const CURRENT_USER_LOG = 'current user:';
const PROFILE_ID_PREFIX = 'n:';

export function profileIdFor(userId: bigint): string {
  return `${PROFILE_ID_PREFIX}${userId}`;
}

export async function appUserFor(
  port: AuthPort | null,
  users: (claims: IdentityClaims) => Promise<AppUserRow>,
): Promise<AppUserRow | null> {
  if (port === null) return null;
  try {
    const sessionUser = await port.currentUser();
    if (sessionUser === null) return null;
    return await users({
      subject: sessionUser.id,
      email: sessionUser.email,
      emailVerified: sessionUser.emailVerified,
      displayName: sessionUser.name,
    });
  } catch (error) {
    console.warn(CURRENT_USER_LOG, error instanceof Error ? error.name : typeof error);
    return null;
  }
}

export async function currentAppUser(): Promise<AppUserRow | null> {
  try {
    return await appUserFor(authPort(), (claims) => linkedAppUser(appDb(), claims));
  } catch (error) {
    if (error instanceof ConfigError) return null;
    throw error;
  }
}

export async function requireAppUser(): Promise<AppUserRow | Response> {
  return (await currentAppUser()) ?? apiError(STATUS_UNAUTHORIZED, UNAUTHENTICATED);
}
