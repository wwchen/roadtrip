import { sql } from 'drizzle-orm';
import {
  bigint,
  bigserial,
  boolean,
  check,
  date,
  foreignKey,
  index,
  integer,
  jsonb,
  pgSequence,
  pgTable,
  primaryKey,
  text,
  timestamp,
  unique,
  varchar,
} from 'drizzle-orm/pg-core';
import { bytea } from '../types/bytea.js';
import { pois } from './catalog.js';
import { atLeast, nullOrAtLeast, textIn } from './constraints.js';

const MIN_COUNT = 0;
const DEFAULT_COUNT = 0;
const RATE_LIMIT_BUCKET_ID_LENGTH = 255;

export const RUN_STATUSES = ['started', 'completed', 'failed'] as const;
export const FETCH_OUTCOMES = ['ok', 'rate_limited', 'upstream_5xx', 'blocked', 'other'] as const;

export type RunStatus = (typeof RUN_STATUSES)[number];
export type FetchOutcome = (typeof FETCH_OUTCOMES)[number];

export const availabilityRunIdSeq = pgSequence('availability_job_run_id_seq');

export const availabilityPoller = pgTable(
  'availability_poller',
  {
    id: bigserial('id', { mode: 'bigint' }).primaryKey(),
    poiId: bigint('poi_id', { mode: 'bigint' }).notNull(),
    provider: text('provider').notNull(),
    parentRef: text('parent_ref').notNull(),
    active: boolean('active').default(true).notNull(),
    nextRunAt: timestamp('next_run_at', { withTimezone: true }).defaultNow().notNull(),
    lastRunAt: timestamp('last_run_at', { withTimezone: true }),
    lastForcePullAt: timestamp('last_force_pull_at', { withTimezone: true }),
    claimToken: text('claim_token'),
    claimedUntil: timestamp('claimed_until', { withTimezone: true }),
    createdAt: timestamp('created_at', { withTimezone: true }).defaultNow().notNull(),
    updatedAt: timestamp('updated_at', { withTimezone: true }).defaultNow().notNull(),
  },
  (table) => [
    foreignKey({
      name: 'availability_poller_poi_id_fkey',
      columns: [table.poiId],
      foreignColumns: [pois.id],
    }).onDelete('cascade'),
    unique('availability_poller_provider_parent_ref_key').on(table.provider, table.parentRef),
    index('availability_poller_due_idx').on(table.nextRunAt).where(sql`active`),
  ],
);

export const availabilityRun = pgTable(
  'availability_run',
  {
    id: bigint('id', { mode: 'bigint' })
      .default(sql`nextval('availability_job_run_id_seq'::regclass)`)
      .notNull(),
    pollerId: bigint('poller_id', { mode: 'bigint' }).notNull(),
    status: text('status').$type<RunStatus>().default('started').notNull(),
    startedAt: timestamp('started_at', { withTimezone: true }).defaultNow().notNull(),
    completedAt: timestamp('completed_at', { withTimezone: true }),
    durationMs: integer('duration_ms'),
    snapshotCount: integer('snapshot_count').default(DEFAULT_COUNT).notNull(),
    error: text('error'),
  },
  (table) => [
    primaryKey({ name: 'availability_job_run_pkey', columns: [table.id] }),
    foreignKey({
      name: 'availability_run_poller_id_fkey',
      columns: [table.pollerId],
      foreignColumns: [availabilityPoller.id],
    }).onDelete('cascade'),
    check('availability_job_run_duration_ms_check', nullOrAtLeast('duration_ms', MIN_COUNT)),
    check('availability_job_run_snapshot_count_check', atLeast('snapshot_count', MIN_COUNT)),
    check('availability_job_run_status_check', textIn('status', RUN_STATUSES)),
    index('availability_run_poller_started_idx').on(table.pollerId, table.startedAt.desc().nullsFirst()),
  ],
);

export const availabilityFetchCall = pgTable(
  'availability_fetch_call',
  {
    id: bigserial('id', { mode: 'bigint' }).primaryKey(),
    runId: bigint('run_id', { mode: 'bigint' }).notNull(),
    provider: text('provider').notNull(),
    parentRef: text('parent_ref').notNull(),
    windowStart: date('window_start').notNull(),
    windowEnd: date('window_end').notNull(),
    outcome: text('outcome').$type<FetchOutcome>().notNull(),
    campsiteCount: integer('campsite_count').default(DEFAULT_COUNT).notNull(),
    durationMs: integer('duration_ms'),
    error: text('error'),
    createdAt: timestamp('created_at', { withTimezone: true }).defaultNow().notNull(),
  },
  (table) => [
    foreignKey({
      name: 'availability_fetch_call_run_id_fkey',
      columns: [table.runId],
      foreignColumns: [availabilityRun.id],
    }).onDelete('cascade'),
    check('availability_fetch_call_duration_ms_check', nullOrAtLeast('duration_ms', MIN_COUNT)),
    check('availability_fetch_call_outcome_check', textIn('outcome', FETCH_OUTCOMES)),
    check('availability_fetch_call_reservable_count_check', atLeast('campsite_count', MIN_COUNT)),
    index('availability_fetch_call_outcome_created_idx').on(
      table.outcome,
      table.createdAt.desc().nullsFirst(),
    ),
    index('availability_fetch_call_run_idx').on(table.runId),
  ],
);

export const vendorRateLimitBucket = pgTable('vendor_rate_limit_bucket', {
  id: varchar('id', { length: RATE_LIMIT_BUCKET_ID_LENGTH }).primaryKey(),
  state: bytea('state'),
});

export const apiCache = pgTable(
  'api_cache',
  {
    namespace: text('namespace').notNull(),
    cacheKey: text('cache_key').notNull(),
    payload: jsonb('payload').notNull(),
    createdAt: timestamp('created_at', { withTimezone: true }).defaultNow().notNull(),
    expiresAt: timestamp('expires_at', { withTimezone: true }).notNull(),
  },
  (table) => [
    primaryKey({ name: 'api_cache_pkey', columns: [table.namespace, table.cacheKey] }),
    index('api_cache_expires_at_idx').on(table.expiresAt),
  ],
);
