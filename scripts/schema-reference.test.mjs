import assert from 'node:assert/strict';
import { mkdtemp, readFile, rm } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';
import { normalize, writeReference } from './schema-reference.mjs';

const PREAMBLE = `--
-- PostgreSQL database dump
--

\\restrict abc123

SET statement_timeout = 0;
SET client_encoding = 'UTF8';
SELECT pg_catalog.set_config('search_path', '', false);`;

const STATEMENTS = [
  'CREATE SCHEMA drizzle;',
  'CREATE TABLE public.zeta (\n    id integer NOT NULL\n);',
  `CREATE FUNCTION public.touch() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN

  NEW.updated_at = now();

  RETURN NEW;
END;
$$;`,
  'CREATE TABLE public.flyway_schema_history (\n    installed_rank integer NOT NULL\n);',
  'CREATE TABLE drizzle.__drizzle_migrations (\n    id integer NOT NULL\n);',
  'CREATE SEQUENCE drizzle.__drizzle_migrations_id_seq;',
  'ALTER TABLE ONLY public.flyway_schema_history\n    ADD CONSTRAINT flyway_schema_history_pk PRIMARY KEY (installed_rank);',
  'CREATE INDEX flyway_schema_history_s_idx ON public.flyway_schema_history USING btree (success);',
  'CREATE TABLE public.alpha (\n    id integer NOT NULL\n);',
  'ALTER TABLE ONLY public.alpha\n    ADD CONSTRAINT alpha_pkey PRIMARY KEY (id);',
];

const dump = (statements) => `${PREAMBLE}\n\n${statements.join('\n\n\n')}\n\n\\unrestrict abc123\n`;
const RAW = dump(STATEMENTS);

test('normalization is idempotent', () => {
  assert.equal(normalize(normalize(RAW)), normalize(RAW));
});

test('normalization drops bookkeeping, preamble and comments', () => {
  const out = normalize(RAW);
  assert.doesNotMatch(out, /flyway_schema_history/);
  assert.doesNotMatch(out, /__drizzle_migrations/);
  assert.doesNotMatch(out, /CREATE SCHEMA drizzle/);
  assert.doesNotMatch(out, /^SET /m);
  assert.doesNotMatch(out, /set_config/);
  assert.doesNotMatch(out, /^--/m);
  assert.doesNotMatch(out, /restrict/);
  assert.doesNotMatch(out, /\n{3,}/);
});

test('normalization sorts statements by object and keeps dollar-quoted bodies whole', () => {
  const out = normalize(RAW);
  const order = ['TABLE public.alpha (', 'FUNCTION public.touch', 'TABLE public.zeta ('];
  const positions = order.map((needle) => out.indexOf(needle));
  assert.deepEqual(positions, [...positions].sort((a, b) => a - b));
  assert.ok(positions.every((position) => position >= 0));
  assert.match(out, /NEW\.updated_at = now\(\);\n\n {2}RETURN NEW;/);
});

test('normalization ignores statement order in the input', () => {
  assert.equal(normalize(dump([...STATEMENTS].reverse())), normalize(RAW));
});

test('reference dump contains the objects Drizzle cannot express', async (context) => {
  const dir = await mkdtemp(path.join(os.tmpdir(), 'roadtrip-schema-reference-'));
  context.after(() => rm(dir, { recursive: true, force: true }));
  const ref = path.join(dir, 'reference.sql');

  await writeReference(ref);
  const sql = await readFile(ref, 'utf8');

  assert.match(sql, /CREATE EXTENSION IF NOT EXISTS postgis/);
  assert.doesNotMatch(sql, /MATERIALIZED VIEW|campground_canonical|campsite_canonical/);
  assert.match(sql, /CREATE VIEW public\.grafana_api_cache_metadata AS/);
  assert.match(sql, /CREATE CONSTRAINT TRIGGER availability_watch_target_prune_empty/);
  assert.match(sql, /CREATE TYPE public\.availability_status AS ENUM/);
  assert.equal((sql.match(/CHECK \(/g) ?? []).length, 76);
  assert.equal((sql.match(/CONSTRAINT \w+ CHECK \(/g) ?? []).length, 76);
  assert.doesNotMatch(sql, /flyway_schema_history|__drizzle_migrations/);
  assert.doesNotMatch(sql, /fuzzystrmatch|tiger|topology/);
  assert.doesNotMatch(sql, /TABLE public\.(availability_snapshot|availability_job)\b/);
  assert.equal(normalize(sql), sql);
});
