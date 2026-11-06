import type { Db } from '@roadtrip/db';
import { sql } from 'drizzle-orm';

export async function pingDatabase(db: Db): Promise<void> {
  await db.execute(sql`SELECT 1`);
}
