import { appDb } from '@/server/appDb';
import type { AuthPort } from '@/server/auth/authPort';
import { authPort } from '@/server/auth/neonAuth';
import { config } from '@/server/config';
import type { MeResponseDto } from '@/server/contract';
import { linkedAppUser, type AppUserRow, type IdentityClaims } from '@/server/repo/appUserRepo';

const ADMIN_ROLE = 'admin';
const ME_LOG = 'me:';
const UNKNOWN_FAILURE = 'UNKNOWN';
const NO_ADMINS: ReadonlySet<string> = new Set();

const DISABLED: MeResponseDto = { authenticated: false, auth_enabled: false, auth_embedded: false };
const ANONYMOUS: MeResponseDto = { authenticated: false, auth_enabled: true, auth_embedded: false };

function failureCodeOf(error: unknown): string {
  if (typeof error === 'object' && error !== null) {
    const { code, name } = error as { code?: unknown; name?: unknown };
    if (typeof code === 'string') return code;
    if (typeof name === 'string') return name;
  }
  return UNKNOWN_FAILURE;
}

export async function meFor(
  port: AuthPort | null,
  users: (claims: IdentityClaims) => Promise<AppUserRow>,
  adminEmails: ReadonlySet<string>,
): Promise<MeResponseDto> {
  if (port === null) return DISABLED;
  try {
    const sessionUser = await port.currentUser();
    if (sessionUser === null) return ANONYMOUS;
    const row = await users({
      subject: sessionUser.id,
      email: sessionUser.email,
      emailVerified: sessionUser.emailVerified,
      displayName: sessionUser.name,
    });
    return {
      authenticated: true,
      auth_enabled: true,
      auth_embedded: false,
      user: {
        id: Number(row.id),
        email: row.email,
        ...(row.displayName === null ? {} : { display_name: row.displayName }),
        email_verified: row.emailVerified,
        roles: adminEmails.has(row.email.toLowerCase()) ? [ADMIN_ROLE] : [],
        theme: row.theme,
      },
    };
  } catch (error) {
    console.warn(ME_LOG, failureCodeOf(error));
    return ANONYMOUS;
  }
}

export function currentMe(): Promise<MeResponseDto> {
  return meFor(authPort(), (claims) => linkedAppUser(appDb(), claims), config.signIn?.adminEmails ?? NO_ADMINS);
}
