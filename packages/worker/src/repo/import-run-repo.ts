import { importRuns, type Db } from '@roadtrip/db';
import { eq } from 'drizzle-orm';

const STATUS_STARTED = 'started';
const STATUS_COMPLETED = 'completed';
const STATUS_FAILED = 'failed';

export async function startImportRun(db: Db, source: string): Promise<bigint> {
  const [row] = await db
    .insert(importRuns)
    .values({ source, status: STATUS_STARTED, startedAt: new Date() })
    .returning({ id: importRuns.id });
  return row!.id;
}

export async function completeImportRun(db: Db, runId: bigint, seenCount: number): Promise<void> {
  await db
    .update(importRuns)
    .set({ status: STATUS_COMPLETED, completedAt: new Date(), seenCount })
    .where(eq(importRuns.id, runId));
}

export async function failImportRun(db: Db, runId: bigint, notes: string): Promise<void> {
  await db
    .update(importRuns)
    .set({ status: STATUS_FAILED, completedAt: new Date(), notes })
    .where(eq(importRuns.id, runId));
}
