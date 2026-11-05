import type { Db } from '@roadtrip/db';
import { sql } from 'drizzle-orm';
import { pgArrayLiteral, type AvailabilityStatus } from './availabilityReadRepo';

export interface Observation {
  campsiteId: bigint;
  date: string;
  status: AvailabilityStatus;
  observedAt: Date;
}

interface CurrentRow extends Record<string, unknown> {
  id: string;
  campsite_id: string;
  target_date: string;
  status: AvailabilityStatus;
}

const cellKey = (campsiteId: bigint | string, date: string) => `${campsiteId}|${date}`;

function latestPerCell(observations: readonly Observation[]): Observation[] {
  const latest = new Map<string, Observation>();
  for (const observation of observations) {
    const key = cellKey(observation.campsiteId, observation.date);
    const held = latest.get(key);
    if (held === undefined || observation.observedAt >= held.observedAt) latest.set(key, observation);
  }
  return [...latest.values()];
}

export async function recordObservations(db: Db, observations: readonly Observation[]): Promise<number> {
  const latest = latestPerCell(observations);
  if (latest.length === 0) return 0;
  return db.transaction(async (tx) => {
    const current = await tx.execute<CurrentRow>(sql`
      SELECT DISTINCT ON (a.campsite_id, a.target_date) a.id, a.campsite_id, a.target_date::text AS target_date, a.status
      FROM availability a
      JOIN unnest(${pgArrayLiteral(latest.map((o) => o.campsiteId))}::bigint[], ${pgArrayLiteral(latest.map((o) => o.date))}::date[])
        AS cell(campsite_id, target_date)
        ON a.campsite_id = cell.campsite_id AND a.target_date = cell.target_date
      ORDER BY a.campsite_id, a.target_date, a.last_observed_at DESC, a.id DESC
    `);
    const currentByCell = new Map(current.map((row) => [cellKey(row.campsite_id, row.target_date), row]));
    const unchanged = latest.filter((o) => currentByCell.get(cellKey(o.campsiteId, o.date))?.status === o.status);
    const changed = latest.filter((o) => currentByCell.get(cellKey(o.campsiteId, o.date))?.status !== o.status);

    if (unchanged.length > 0) {
      const ids = unchanged.map((o) => currentByCell.get(cellKey(o.campsiteId, o.date))!.id);
      await tx.execute(sql`
        UPDATE availability AS a
        SET last_observed_at = GREATEST(a.last_observed_at, seen.observed_at)
        FROM unnest(${pgArrayLiteral(ids)}::bigint[], ${pgArrayLiteral(unchanged.map((o) => o.observedAt.toISOString()))}::timestamptz[])
          AS seen(id, observed_at)
        WHERE a.id = seen.id
      `);
    }
    if (changed.length === 0) return 0;
    const previous = changed.map((o) => currentByCell.get(cellKey(o.campsiteId, o.date))?.id ?? 'NULL');
    const inserted = await tx.execute(sql`
      INSERT INTO availability (campsite_id, target_date, status, last_observed_at, previous_id)
      SELECT run.campsite_id, run.target_date, run.status::availability_status, run.observed_at, run.previous_id
      FROM unnest(
        ${pgArrayLiteral(changed.map((o) => o.campsiteId))}::bigint[],
        ${pgArrayLiteral(changed.map((o) => o.date))}::date[],
        ${pgArrayLiteral(changed.map((o) => o.status))}::text[],
        ${pgArrayLiteral(changed.map((o) => o.observedAt.toISOString()))}::timestamptz[],
        ${pgArrayLiteral(previous)}::bigint[]
      ) AS run(campsite_id, target_date, status, observed_at, previous_id)
      ON CONFLICT DO NOTHING
      RETURNING id
    `);
    return inserted.length;
  });
}
