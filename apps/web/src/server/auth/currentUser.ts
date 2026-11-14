import { ACTIVE_USER_STATUS } from '@roadtrip/domain';
import { appDb } from '@/server/appDb';
import { ConfigError } from '@/server/config';
import { linkedAppUser, type AppUserRow, type IdentityClaims } from '@/server/repo/appUserRepo';
import type { ActiveUser } from '@/server/service/auth/model';
import type { AuthPort } from './authPort';
import { authPort } from './neonAuth';

const CURRENT_USER_LOG = 'current user:';

export async function appUserFor(
  port: AuthPort | null,
  users: (claims: IdentityClaims) => Promise<AppUserRow>,
): Promise<ActiveUser | null> {
  if (port === null) return null;
  try {
    const sessionUser = await port.currentUser();
    if (sessionUser === null) return null;
    const row = await users({
      subject: sessionUser.id,
      email: sessionUser.email,
      emailVerified: sessionUser.emailVerified,
      displayName: sessionUser.name,
    });
    return row.status === ACTIVE_USER_STATUS ? { ...row, status: row.status } : null;
  } catch (error) {
    console.warn(CURRENT_USER_LOG, error instanceof Error ? error.name : typeof error);
    return null;
  }
}

export async function currentAppUser(): Promise<ActiveUser | null> {
  try {
    return await appUserFor(authPort(), (claims) => linkedAppUser(appDb(), claims));
  } catch (error) {
    if (error instanceof ConfigError) return null;
    throw error;
  }
}
