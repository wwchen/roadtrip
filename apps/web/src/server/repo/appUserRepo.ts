import { appUser, userIdentity, type Db, type UserTheme } from '@roadtrip/db';
import { and, eq, sql } from 'drizzle-orm';

export const NEON_AUTH_PROVIDER = 'neon-auth';

export interface IdentityClaims {
  subject: string;
  email: string;
  emailVerified: boolean;
  displayName: string | null;
}

export interface AppUserRow {
  id: bigint;
  email: string;
  emailVerified: boolean;
  displayName: string | null;
  theme: UserTheme;
}

export class EmailTakenError extends Error {
  constructor() {
    super('the email belongs to another account and this identity has not verified it');
    this.name = 'EmailTakenError';
  }
}

type Executor = Pick<Db, 'select' | 'insert' | 'update'>;

const userColumns = {
  id: appUser.id,
  email: appUser.email,
  emailVerified: appUser.emailVerified,
  displayName: appUser.displayName,
  theme: appUser.theme,
};

async function byIdentity(db: Executor, subject: string): Promise<AppUserRow | undefined> {
  const [row] = await db
    .select(userColumns)
    .from(userIdentity)
    .innerJoin(appUser, eq(appUser.id, userIdentity.userId))
    .where(and(eq(userIdentity.provider, NEON_AUTH_PROVIDER), eq(userIdentity.subject, subject)));
  return row;
}

async function byEmail(db: Executor, email: string): Promise<AppUserRow | undefined> {
  const [row] = await db
    .select(userColumns)
    .from(appUser)
    .where(sql`lower(${appUser.email}) = lower(${email})`);
  return row;
}

async function refreshed(db: Executor, user: AppUserRow, claims: IdentityClaims): Promise<AppUserRow> {
  if (user.emailVerified === claims.emailVerified && user.displayName === claims.displayName) return user;
  const [row] = await db
    .update(appUser)
    .set({ emailVerified: claims.emailVerified, displayName: claims.displayName, updatedAt: sql`now()` })
    .where(eq(appUser.id, user.id))
    .returning(userColumns);
  return row ?? user;
}

async function created(db: Db, claims: IdentityClaims): Promise<AppUserRow> {
  return db.transaction(async (tx) => {
    const [inserted] = await tx
      .insert(appUser)
      .values({ email: claims.email, emailVerified: claims.emailVerified, displayName: claims.displayName })
      .onConflictDoNothing()
      .returning(userColumns);
    let owner = inserted;
    if (owner === undefined) {
      const linked = await byIdentity(tx, claims.subject);
      if (linked !== undefined) return linked;
      const existing = await byEmail(tx, claims.email);
      if (existing === undefined || !claims.emailVerified) throw new EmailTakenError();
      owner = existing;
    }
    await tx
      .insert(userIdentity)
      .values({ userId: owner.id, provider: NEON_AUTH_PROVIDER, subject: claims.subject })
      .onConflictDoNothing();
    const linked = await byIdentity(tx, claims.subject);
    if (linked === undefined) throw new EmailTakenError();
    return linked;
  });
}

export async function linkedAppUser(db: Db, claims: IdentityClaims): Promise<AppUserRow> {
  const existing = await byIdentity(db, claims.subject);
  if (existing !== undefined) return refreshed(db, existing, claims);
  return refreshed(db, await created(db, claims), claims);
}
