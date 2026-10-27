import { sql } from 'drizzle-orm';
import {
  bigint,
  bigserial,
  check,
  foreignKey,
  index,
  integer,
  jsonb,
  pgTable,
  text,
  timestamp,
} from 'drizzle-orm/pg-core';
import { textIn } from './constraints.js';

export const INGEST_PHASE_KINDS = ['target', 'fetch', 'import'] as const;
export const INGEST_STATUSES = ['started', 'completed', 'failed', 'aborted'] as const;

export type IngestPhaseKind = (typeof INGEST_PHASE_KINDS)[number];
export type IngestStatus = (typeof INGEST_STATUSES)[number];

export const ingestRuns = pgTable(
  'ingest_runs',
  {
    id: bigserial('id', { mode: 'bigint' }).primaryKey(),
    target: text('target').notNull(),
    phase: text('phase').notNull(),
    phaseKind: text('phase_kind').$type<IngestPhaseKind>().notNull(),
    status: text('status').$type<IngestStatus>().notNull(),
    triggeredBy: text('triggered_by').notNull(),
    parentRunId: bigint('parent_run_id', { mode: 'bigint' }),
    startedAt: timestamp('started_at', { withTimezone: true }).defaultNow().notNull(),
    completedAt: timestamp('completed_at', { withTimezone: true }),
    exitCode: integer('exit_code'),
    counts: jsonb('counts').default({}).notNull(),
    notes: text('notes'),
  },
  (table) => [
    foreignKey({
      name: 'ingest_runs_parent_run_id_fkey',
      columns: [table.parentRunId],
      foreignColumns: [table.id],
    }).onDelete('cascade'),
    check('ingest_runs_phase_kind_check', textIn('phase_kind', INGEST_PHASE_KINDS)),
    check('ingest_runs_status_check', textIn('status', INGEST_STATUSES)),
    index('ingest_runs_parent_idx').on(table.parentRunId),
    index('ingest_runs_started_only_idx')
      .on(table.startedAt)
      .where(sql`status = 'started' AND phase_kind = 'target'`),
    index('ingest_runs_target_started_idx').on(table.target, table.startedAt.desc().nullsFirst()),
  ],
);

export const importRuns = pgTable('import_runs', {
  id: bigserial('id', { mode: 'bigint' }).primaryKey(),
  source: text('source').notNull(),
  status: text('status').notNull(),
  startedAt: timestamp('started_at', { withTimezone: true }).defaultNow().notNull(),
  completedAt: timestamp('completed_at', { withTimezone: true }),
  seenCount: integer('seen_count'),
  notes: text('notes'),
});
