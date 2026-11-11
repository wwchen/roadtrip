import { userBookingCredentials, type Db } from '@roadtrip/db';
import { and, eq, sql } from 'drizzle-orm';

export const RECGOV_PROVIDER = 'recgov';

export interface StoredCredentials {
  username: string;
  secretCipher: Buffer;
}

const ownedBy = (userId: bigint, provider: string) =>
  and(eq(userBookingCredentials.userId, userId), eq(userBookingCredentials.provider, provider));

export async function findCredentials(db: Db, userId: bigint, provider: string): Promise<StoredCredentials | null> {
  const [row] = await db
    .select({ username: userBookingCredentials.username, secretCipher: userBookingCredentials.secretCipher })
    .from(userBookingCredentials)
    .where(ownedBy(userId, provider));
  return row === undefined ? null : { username: row.username, secretCipher: Buffer.from(row.secretCipher) };
}

export async function upsertCredentials(db: Db, userId: bigint, provider: string, username: string, secretCipher: Buffer): Promise<void> {
  await db
    .insert(userBookingCredentials)
    .values({ userId, provider, username, secretCipher })
    .onConflictDoUpdate({
      target: [userBookingCredentials.userId, userBookingCredentials.provider],
      set: { username, secretCipher, updatedAt: sql`now()` },
    });
}

export async function removeCredentials(db: Db, userId: bigint, provider: string): Promise<boolean> {
  const removed = await db.delete(userBookingCredentials).where(ownedBy(userId, provider)).returning({ userId: userBookingCredentials.userId });
  return removed.length > 0;
}
