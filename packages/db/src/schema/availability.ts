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
  pgEnum,
  pgTable,
  primaryKey,
  text,
  timestamp,
  uniqueIndex,
} from 'drizzle-orm/pg-core';
import { appUser } from './auth.js';
import { campsites, pois } from './catalog.js';
import { atLeast, jsonbObject, moreThan, nullOrTextIn, textIn } from './constraints.js';
import { availabilityPoller, availabilityRun } from './scheduler.js';

const MIN_CADENCE_SEC = 5;
const EMPTY_CARDINALITY = 0;

export const AVAILABILITY_STATUSES = [
  'first_come',
  'reserved',
  'available',
  'closed',
  'unknown',
  'past',
] as const;

export const WATCH_STATUSES = ['active', 'paused', 'done'] as const;
export const WATCH_DONE_REASONS = ['triggered', 'elapsed'] as const;

export type WatchStatus = (typeof WATCH_STATUSES)[number];
export type WatchDoneReason = (typeof WATCH_DONE_REASONS)[number];

export const availabilityStatus = pgEnum('availability_status', AVAILABILITY_STATUSES);

export const availability = pgTable(
  'availability',
  {
    id: bigserial('id', { mode: 'bigint' }).primaryKey(),
    campsiteId: bigint('campsite_id', { mode: 'bigint' }).notNull(),
    targetDate: date('target_date').notNull(),
    status: availabilityStatus('status').notNull(),
    lastObservedAt: timestamp('last_observed_at', { withTimezone: true }).notNull(),
    runId: bigint('run_id', { mode: 'bigint' }),
    previousId: bigint('previous_id', { mode: 'bigint' }),
  },
  (table) => [
    foreignKey({
      name: 'availability_campsite_id_fkey',
      columns: [table.campsiteId],
      foreignColumns: [campsites.id],
    }).onDelete('cascade'),
    foreignKey({
      name: 'availability_run_id_fkey',
      columns: [table.runId],
      foreignColumns: [availabilityRun.id],
    }).onDelete('set null'),
    foreignKey({
      name: 'availability_previous_id_fkey',
      columns: [table.previousId],
      foreignColumns: [table.id],
    }).onDelete('set null'),
    index('availability_campsite_current_idx').on(
      table.campsiteId,
      table.targetDate,
      table.lastObservedAt.desc().nullsFirst(),
    ),
    index('availability_run_idx').on(table.runId).where(sql`run_id IS NOT NULL`),
    uniqueIndex('availability_previous_id_uq')
      .on(table.previousId)
      .where(sql`previous_id IS NOT NULL`),
  ],
);

export const availabilityWatch = pgTable(
  'availability_watch',
  {
    id: bigserial('id', { mode: 'bigint' }).primaryKey(),
    ownerUserId: bigint('owner_user_id', { mode: 'bigint' }).notNull(),
    startDate: date('start_date').notNull(),
    endDate: date('end_date').notNull(),
    cadenceSec: integer('cadence_sec'),
    status: text('status').$type<WatchStatus>().default('active').notNull(),
    doneReason: text('done_reason').$type<WatchDoneReason>(),
    stopWhenTriggered: boolean('stop_when_triggered').default(true).notNull(),
    triggerKinds: text('trigger_kinds').array().notNull(),
    triggerConfig: jsonb('trigger_config').default({}).notNull(),
    campsiteFilters: jsonb('campsite_filters').default({}).notNull(),
    magicLinkToken: text('magic_link_token'),
    createdAt: timestamp('created_at', { withTimezone: true }).defaultNow().notNull(),
    updatedAt: timestamp('updated_at', { withTimezone: true }).defaultNow().notNull(),
  },
  (table) => [
    foreignKey({
      name: 'availability_watch_owner_user_id_fkey',
      columns: [table.ownerUserId],
      foreignColumns: [appUser.id],
    }),
    check('availability_watch_cadence_sec_check', atLeast(table.cadenceSec, MIN_CADENCE_SEC)),
    check('availability_watch_date_window_check', sql`end_date > start_date`),
    check(
      'availability_watch_done_reason_check',
      nullOrTextIn(table.doneReason, WATCH_DONE_REASONS),
    ),
    check('availability_watch_reservable_filters_check', jsonbObject(table.campsiteFilters)),
    check('availability_watch_status_check', textIn(table.status, WATCH_STATUSES)),
    check('availability_watch_trigger_config_check', jsonbObject(table.triggerConfig)),
    check(
      'availability_watch_trigger_kinds_check',
      moreThan('cardinality(trigger_kinds)', EMPTY_CARDINALITY),
    ),
    index('availability_watch_active_idx').on(table.status).where(sql`status = 'active'`),
    index('availability_watch_owner_idx').on(table.ownerUserId),
  ],
);

export const availabilityWatchTarget = pgTable(
  'availability_watch_target',
  {
    id: bigserial('id', { mode: 'bigint' }).primaryKey(),
    watchId: bigint('watch_id', { mode: 'bigint' }).notNull(),
    poiId: bigint('poi_id', { mode: 'bigint' }),
    campsiteId: bigint('campsite_id', { mode: 'bigint' }),
  },
  (table) => [
    foreignKey({
      name: 'availability_watch_target_watch_id_fkey',
      columns: [table.watchId],
      foreignColumns: [availabilityWatch.id],
    }).onDelete('cascade'),
    foreignKey({
      name: 'availability_watch_target_poi_id_fkey',
      columns: [table.poiId],
      foreignColumns: [pois.id],
    }).onDelete('cascade'),
    foreignKey({
      name: 'availability_watch_target_campsite_id_fkey',
      columns: [table.campsiteId],
      foreignColumns: [campsites.id],
    }).onDelete('cascade'),
    check(
      'availability_watch_target_check',
      sql`(poi_id IS NOT NULL) <> (campsite_id IS NOT NULL)`,
    ),
    index('availability_watch_target_watch_idx').on(table.watchId),
    index('availability_watch_target_poi_idx').on(table.poiId).where(sql`poi_id IS NOT NULL`),
    index('availability_watch_target_campsite_idx')
      .on(table.campsiteId)
      .where(sql`campsite_id IS NOT NULL`),
  ],
);

export const availabilityWatchPoller = pgTable(
  'availability_watch_poller',
  {
    watchId: bigint('watch_id', { mode: 'bigint' }).notNull(),
    pollerId: bigint('poller_id', { mode: 'bigint' }).notNull(),
  },
  (table) => [
    primaryKey({
      name: 'availability_watch_poller_pkey',
      columns: [table.watchId, table.pollerId],
    }),
    foreignKey({
      name: 'availability_watch_poller_watch_id_fkey',
      columns: [table.watchId],
      foreignColumns: [availabilityWatch.id],
    }).onDelete('cascade'),
    foreignKey({
      name: 'availability_watch_poller_poller_id_fkey',
      columns: [table.pollerId],
      foreignColumns: [availabilityPoller.id],
    }).onDelete('cascade'),
    index('availability_watch_poller_poller_idx').on(table.pollerId),
  ],
);
