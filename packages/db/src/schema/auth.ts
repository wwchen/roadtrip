import { sql } from 'drizzle-orm';
import {
  bigint,
  bigserial,
  boolean,
  check,
  foreignKey,
  index,
  pgTable,
  primaryKey,
  text,
  timestamp,
  unique,
  uniqueIndex,
} from 'drizzle-orm/pg-core';
import { bytea } from '../types/bytea.js';
import { equalsText, textIn } from './constraints.js';

export const USER_STATUSES = ['active', 'disabled'] as const;
export const USER_THEMES = ['light', 'dark', 'system'] as const;
export const USER_ROLES = ['admin'] as const;

export type UserStatus = (typeof USER_STATUSES)[number];
export type UserTheme = (typeof USER_THEMES)[number];
export type UserRole = (typeof USER_ROLES)[number];

export const appUser = pgTable(
  'app_user',
  {
    id: bigserial('id', { mode: 'bigint' }).primaryKey(),
    email: text('email').notNull(),
    emailVerified: boolean('email_verified').default(false).notNull(),
    displayName: text('display_name'),
    status: text('status').$type<UserStatus>().default('active').notNull(),
    theme: text('theme').$type<UserTheme>().default('system').notNull(),
    createdAt: timestamp('created_at', { withTimezone: true }).defaultNow().notNull(),
    updatedAt: timestamp('updated_at', { withTimezone: true }).defaultNow().notNull(),
  },
  (table) => [
    check('app_user_status_check', textIn(table.status, USER_STATUSES)),
    check('app_user_theme_check', textIn(table.theme, USER_THEMES)),
    uniqueIndex('app_user_email_lower_uq').on(sql`lower(email)`),
  ],
);

export const userIdentity = pgTable(
  'user_identity',
  {
    id: bigserial('id', { mode: 'bigint' }).primaryKey(),
    userId: bigint('user_id', { mode: 'bigint' }).notNull(),
    provider: text('provider').notNull(),
    subject: text('subject').notNull(),
    upstreamProvider: text('upstream_provider'),
    upstreamSubject: text('upstream_subject'),
    emailVerifiedAt: timestamp('email_verified_at', { withTimezone: true }),
    createdAt: timestamp('created_at', { withTimezone: true }).defaultNow().notNull(),
  },
  (table) => [
    foreignKey({
      name: 'user_identity_user_id_fkey',
      columns: [table.userId],
      foreignColumns: [appUser.id],
    }).onDelete('cascade'),
    unique('user_identity_provider_subject_key').on(table.provider, table.subject),
    index('user_identity_user_idx').on(table.userId),
    index('user_identity_upstream_idx')
      .on(table.upstreamProvider, table.upstreamSubject)
      .where(sql`upstream_subject IS NOT NULL`),
  ],
);

export const userSession = pgTable(
  'user_session',
  {
    id: bigserial('id', { mode: 'bigint' }).primaryKey(),
    userId: bigint('user_id', { mode: 'bigint' }).notNull(),
    tokenHash: bytea('token_hash').notNull(),
    createdAt: timestamp('created_at', { withTimezone: true }).defaultNow().notNull(),
    expiresAt: timestamp('expires_at', { withTimezone: true }).notNull(),
    revokedAt: timestamp('revoked_at', { withTimezone: true }),
  },
  (table) => [
    foreignKey({
      name: 'user_session_user_id_fkey',
      columns: [table.userId],
      foreignColumns: [appUser.id],
    }).onDelete('cascade'),
    unique('user_session_token_hash_key').on(table.tokenHash),
    index('user_session_user_idx').on(table.userId),
    index('user_session_expires_idx').on(table.expiresAt),
  ],
);

export const userRole = pgTable(
  'user_role',
  {
    userId: bigint('user_id', { mode: 'bigint' }).notNull(),
    role: text('role').$type<UserRole>().notNull(),
  },
  (table) => [
    primaryKey({ name: 'user_role_pkey', columns: [table.userId, table.role] }),
    foreignKey({
      name: 'user_role_user_id_fkey',
      columns: [table.userId],
      foreignColumns: [appUser.id],
    }).onDelete('cascade'),
    check('user_role_role_check', equalsText(table.role, USER_ROLES[0])),
  ],
);

export const userSettings = pgTable(
  'user_settings',
  {
    userId: bigint('user_id', { mode: 'bigint' }).primaryKey(),
    notificationEmail: text('notification_email'),
    slackTokenCipher: bytea('slack_token_cipher'),
    slackTokenHint: text('slack_token_hint'),
    slackChannel: text('slack_channel'),
    recgovUsername: text('recgov_username'),
    recgovPasswordCipher: bytea('recgov_password_cipher'),
    createdAt: timestamp('created_at', { withTimezone: true }).defaultNow().notNull(),
    updatedAt: timestamp('updated_at', { withTimezone: true }).defaultNow().notNull(),
  },
  (table) => [
    foreignKey({
      name: 'user_settings_user_id_fkey',
      columns: [table.userId],
      foreignColumns: [appUser.id],
    }).onDelete('cascade'),
  ],
);

export const userBookingCredentials = pgTable(
  'user_booking_credentials',
  {
    userId: bigint('user_id', { mode: 'bigint' }).notNull(),
    provider: text('provider').notNull(),
    username: text('username').notNull(),
    secretCipher: bytea('secret_cipher').notNull(),
    updatedAt: timestamp('updated_at', { withTimezone: true }).defaultNow().notNull(),
  },
  (table) => [
    primaryKey({
      name: 'user_booking_credentials_pkey',
      columns: [table.userId, table.provider],
    }),
    foreignKey({
      name: 'user_booking_credentials_user_id_fkey',
      columns: [table.userId],
      foreignColumns: [appUser.id],
    }).onDelete('cascade'),
  ],
);
