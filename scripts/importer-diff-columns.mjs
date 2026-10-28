#!/usr/bin/env node
//
// What the importer differential compares, and the SQL that compares it.
//
// The two importers write into two databases in one container. The Kotlin rows
// are copied into a `reference` schema beside the TypeScript rows, so every
// statement below is a single-database query over two schemas:
//
//   reference.<t>  the Kotlin importer's rows
//   public.<t>     the TypeScript importer's rows
//
// Three rulings are implemented here rather than in the harness, because they
// are statements about the schema and have to move when the schema moves:
//
// 1. The column list the SQL compares is derived from `getTableConfig`, so a
//    column added to the Drizzle schema is compared from the moment it exists
//    and the harness is never quietly narrower than the table. Each table also
//    names its compared columns by hand, which is what
//    `importer-differential.test.mjs`'s completeness test checks against the
//    live schema; see `spec()` for why both lists are here.
// 2. Columns are compared with `ROW(...) IS DISTINCT FROM ROW(...)`, which
//    applies each column's own `=`. `jsonb` therefore compares as `jsonb`
//    (`{"minimum": 12.0}` equals `{"minimum": 12}`) and `geometry` compares as
//    `geometry` (exact coordinates and vertex order under PostGIS 3.4, not the
//    bounding box). `textDiffSql` below is the rejected alternative, kept so a
//    test can show the pair this ruling tolerates would otherwise be reported.
// 3. Every key is a `text[]`, so a `Difference.key` is one shape for every
//    table and `FULL OUTER JOIN ... USING (diff_key)` needs no per-table
//    plumbing. `pois` is keyed on its link table's name followed by the owner's
//    natural key, because it is reached through three different link tables
//    whose owner keys have different arities.

import { getTableConfig } from 'drizzle-orm/pg-core';
import {
  campgrounds,
  campsites,
  importRuns,
  planetFitnessLocations,
  poiCampgrounds,
  poiPlanetFitnessLocations,
  poiTeslaSuperchargers,
  pois,
  teslaSuperchargers,
} from '@roadtrip/db';

export const KOTLIN_SCHEMA = 'reference';
export const TYPESCRIPT_SCHEMA = 'public';

export const SIDES = [
  { name: 'kotlin', schema: KOTLIN_SCHEMA },
  { name: 'typescript', schema: TYPESCRIPT_SCHEMA },
];

const KEY_COLUMN = 'diff_key';
const DUPLICATE_KEY_SAMPLE_LIMIT = 10;

// The surrogate key and the two timestamps the database sets itself. Compared
// nowhere: `id` is a sequence value and the timestamps are wall-clock, so both
// differ between two runs of the same importer.
const SURROGATE_AND_CLOCK = {
  id: 'surrogate sequence value; the row is identified by its natural key',
  created_at: 'wall clock set by the database default',
  updated_at: 'wall clock set by the database default',
};

// The sentinel a non-nullable column is blanked to under --prove-update-keys.
// Most non-nullable scalars take BLANK_TEXT, which satisfies the `nonBlank`
// CHECKs on name and status. The exceptions carry a CHECK that enumerates
// allowed values (campsites.kind, pois.poi_type) or are not scalars at all
// (pois.geom), so a generic sentinel would fail the CHECK rather than blank the
// column.
const BLANK_TEXT = "'blanked'";
const BLANK_JSONB_OBJECT = "'{}'::jsonb";
const BLANK_JSONB_ARRAY = "'[]'::jsonb";
const BLANK_INTEGER = '0';
const BLANK_BOOLEAN = 'false';
const BLANK_NULL = 'NULL';

const JSONB_COLUMN_TYPE = 'PgJsonb';
const BOOLEAN_COLUMN_TYPE = 'PgBoolean';
const NUMERIC_COLUMN_TYPES = ['PgInteger', 'PgDoublePrecision', 'PgBigInt53', 'PgBigInt64'];

const quote = (schema, table) => `${schema}.${table}`;

function configOf(table) {
  const config = getTableConfig(table);
  return { name: config.name, columns: config.columns };
}

export function columnsOf(tableName) {
  const spec = DIFFED_TABLES.find((candidate) => candidate.table === tableName);
  if (spec === undefined) throw new Error(`no diffed table named '${tableName}'`);
  return spec.columnNames;
}

function blankValueFor(column, overrides) {
  if (Object.hasOwn(overrides, column.name)) return overrides[column.name];
  if (!column.notNull) return BLANK_NULL;
  if (column.columnType === JSONB_COLUMN_TYPE) {
    return Array.isArray(column.default) ? BLANK_JSONB_ARRAY : BLANK_JSONB_OBJECT;
  }
  if (column.columnType === BOOLEAN_COLUMN_TYPE) return BLANK_BOOLEAN;
  if (NUMERIC_COLUMN_TYPES.includes(column.columnType)) return BLANK_INTEGER;
  return BLANK_TEXT;
}

// `compared` is written out by hand; `comparedForDiff` is derived from
// `getTableConfig`. Both, deliberately, and they are not redundant:
//
//   * the SQL uses the derived list, so a column added to the schema is
//     compared from the moment it exists — the harness is never quietly
//     narrower than the table;
//   * the hand-written list is what `importer-differential.test.mjs`'s
//     completeness test checks against the live schema, so a column added or
//     renamed fails that test until someone has looked at it and either listed
//     it here or ignored it with a reason.
//
// Deriving both would make that test tautological: `columns - ignored` is by
// construction exactly the set of columns that are not ignored, so no schema
// change could ever fail it, and a new column would reach the diff with nobody
// having decided it belongs there.
function spec(table, { key = [], compared, ignored, joins, derived = {}, blankOverrides = {}, rowSelect }) {
  const { name, columns } = configOf(table);
  const columnNames = columns.map((column) => column.name);
  const live = columnNames.filter((column) => !Object.hasOwn(ignored, column));
  return {
    table: name,
    key,
    compared,
    ignored,
    joins,
    derived,
    columnNames,
    columns,
    blankOverrides,
    rowSelect,
    comparedForDiff: [...live, ...Object.keys(derived)],
  };
}

// pois is reached through its three link tables; `owner` names the table whose
// natural key identifies the row on both sides, since a poi has no natural key
// of its own.
const POI_JOINS = [
  {
    link: configOf(poiCampgrounds).name,
    linkColumns: configOf(poiCampgrounds).columns.map((column) => column.name),
    ownerColumn: 'campground_id',
    owner: configOf(campgrounds).name,
    ownerKey: ['data_provider', 'data_provider_ref'],
  },
  {
    link: configOf(poiTeslaSuperchargers).name,
    linkColumns: configOf(poiTeslaSuperchargers).columns.map((column) => column.name),
    ownerColumn: 'tesla_supercharger_id',
    owner: configOf(teslaSuperchargers).name,
    ownerKey: ['location_slug'],
  },
  {
    link: configOf(poiPlanetFitnessLocations).name,
    linkColumns: configOf(poiPlanetFitnessLocations).columns.map((column) => column.name),
    ownerColumn: 'planet_fitness_location_id',
    owner: configOf(planetFitnessLocations).name,
    ownerKey: ['location_id'],
  },
];

// import_runs is append-only: the orchestrator inserts one row per run, so
// `--passes 2` leaves two rows per source and `source` stops being unique.
// The side select therefore compares the newest run per source and carries the
// number of runs as a derived column, so a side that opened a different number
// of runs is still reported.
const IMPORT_RUNS_DERIVED = {
  runs: 'the number of import_runs rows for this source, since only the newest is compared',
};

function importRunsRowSelect(schema, comparedColumns) {
  const inner = comparedColumns.filter((column) => column !== 'runs');
  return `SELECT ARRAY[source::text] AS ${KEY_COLUMN}, ${inner.join(', ')}, runs
          FROM (
            SELECT ${inner.join(', ')},
                   count(*) OVER (PARTITION BY source) AS runs,
                   row_number() OVER (PARTITION BY source ORDER BY id DESC) AS newest
            FROM ${quote(schema, 'import_runs')}
          ) ranked
          WHERE newest = 1`;
}

function poisRowSelect(schema, comparedColumns) {
  const columns = comparedColumns.map((column) => `p.${column}`).join(', ');
  return POI_JOINS.map(
    (join) => `SELECT ARRAY['${join.link}', ${join.ownerKey.map((column) => `o.${column}::text`).join(', ')}] AS ${KEY_COLUMN},
                      ${columns}
               FROM ${quote(schema, 'pois')} p
               JOIN ${quote(schema, join.link)} l ON l.poi_id = p.id
               JOIN ${quote(schema, join.owner)} o ON o.id = l.${join.ownerColumn}`,
  ).join('\n UNION ALL\n');
}

export const DIFFED_TABLES = [
  spec(campgrounds, {
    key: ['data_provider', 'data_provider_ref'],
    compared: [
      'data_provider', 'data_provider_ref', 'booking_provider', 'booking_provider_ref', 'booking_aliases',
      'name', 'parent_name', 'kind', 'status', 'status_description', 'short_description',
      'medium_description', 'long_description', 'reservation_url', 'big_rig_friendly',
      'has_pull_through_sites', 'max_rv_length', 'max_trailer_length', 'location', 'contact', 'connections',
      'management', 'price', 'metadata', 'amenities', 'alerts', 'cell_service', 'links', 'photos',
      'default_campsite_schedule', 'geometry_provenance', 'source_payload', 'deleted_at',
    ],
    ignored: { ...SURROGATE_AND_CLOCK },
  }),
  spec(campsites, {
    key: ['data_provider', 'data_provider_ref'],
    compared: [
      'data_provider', 'data_provider_ref', 'booking_provider', 'booking_provider_ref', 'booking_aliases',
      'name', 'kind', 'kind_listed', 'loop_name', 'description', 'reservation_url', 'latitude', 'longitude',
      'ada_accessible', 'electric_hookups', 'water_hookups', 'sewer_hookups', 'firepit', 'picnic_table',
      'pull_through', 'driveway_length', 'max_cars', 'min_people', 'max_people', 'max_rv_length',
      'max_trailer_length', 'attributes', 'equipment', 'photos', 'price', 'schedule', 'source_payload',
      'deleted_at',
    ],
    ignored: {
      ...SURROGATE_AND_CLOCK,
      campground_id:
        'surrogate FK to campgrounds.id; the parent is compared through its own natural key in the campgrounds diff',
    },
    // campsites_kind_wire_check enumerates the allowed kinds, so the generic
    // non-empty sentinel would fail the CHECK rather than blank the column.
    blankOverrides: { kind: "'tent'" },
  }),
  spec(teslaSuperchargers, {
    key: ['location_slug'],
    compared: [
      'location_slug', 'location_guid', 'common_site_name', 'site_status', 'access_type', 'open_to_public',
      'open_to_non_teslas', 'trailer_friendly', 'twenty_four_seven', 'stall_count', 'max_power_kw',
      'region', 'country', 'time_zone', 'info_url', 'address', 'amenities', 'availability_profile',
      'hardware_counts', 'pricebooks', 'index_payload', 'detail_payload', 'deleted_at',
    ],
    ignored: { ...SURROGATE_AND_CLOCK },
  }),
  spec(planetFitnessLocations, {
    key: ['location_id'],
    compared: [
      'location_id', 'name', 'phone', 'info_url', 'opening_hours', 'region', 'country', 'address',
      'amenities', 'payload', 'deleted_at',
    ],
    ignored: { ...SURROGATE_AND_CLOCK },
  }),
  spec(pois, {
    compared: ['poi_type', 'geom', 'cadence_override_sec', 'deleted_at'],
    ignored: { ...SURROGATE_AND_CLOCK },
    joins: POI_JOINS,
    rowSelect: poisRowSelect,
    // pois_poi_type_check enumerates the allowed types, and geom is a
    // non-nullable geometry rather than a scalar.
    blankOverrides: {
      poi_type: "'campground'",
      geom: "'SRID=4326;POINT(0 0)'::geometry",
    },
  }),
  spec(importRuns, {
    key: ['source'],
    compared: ['source', 'status', 'seen_count', 'notes'],
    ignored: {
      id: 'surrogate sequence value; the row is identified by its source',
      started_at: 'wall clock set when the run opens',
      completed_at: 'wall clock set when the run closes',
    },
    derived: IMPORT_RUNS_DERIVED,
    rowSelect: importRunsRowSelect,
  }),
];

// Every table the Kotlin rows have to be copied into, which is the diffed set
// plus the three link tables `pois` is joined through. Without the link tables
// the pois diff would join an empty relation and report nothing at all.
//
// `columns` is why each entry carries more than a name. The two databases are
// migrated by different tools and their physical column order differs — which
// is exactly why scripts/schema-reference.mjs sorts a table's columns before
// comparing two dumps. A binary COPY is positional, so the column list has to
// be named on both sides of the pipe or a boolean lands in a double-precision
// column and Postgres reports "incorrect binary data format". Order comes from
// the Drizzle schema, which both the source and the destination then agree on.
//
// Listed parents-before-children so a data-only copy satisfies the foreign
// keys as it goes: campgrounds before campsites, pois and its owners before
// the link tables.
export const COPIED_TABLES = [
  ...DIFFED_TABLES.map((diffed) => ({ name: diffed.table, columns: diffed.columnNames })),
  ...POI_JOINS.map((join) => ({ name: join.link, columns: join.linkColumns })),
];

// The tables whose emptiness means an importer did not run. import_runs is
// excluded deliberately: the orchestrator opens a run before any adapter
// produces a candidate, so a no-op import still leaves rows there and a guard
// that counted them would pass the vacuous run it exists to catch.
export const SUBSTANTIVE_TABLES = DIFFED_TABLES.filter(
  (diffed) => diffed.table !== configOf(importRuns).name,
).map((diffed) => diffed.table);

function rowSelect(diffed, schema, columns) {
  if (diffed.rowSelect !== undefined) return diffed.rowSelect(schema, columns);
  const key = diffed.key.map((column) => `${column}::text`).join(', ');
  return `SELECT ARRAY[${key}] AS ${KEY_COLUMN}, ${columns.join(', ')} FROM ${quote(schema, diffed.table)}`;
}

function sides(diffed, columns) {
  return `kotlin AS (
            ${rowSelect(diffed, KOTLIN_SCHEMA, columns)}
          ),
          typescript AS (
            ${rowSelect(diffed, TYPESCRIPT_SCHEMA, columns)}
          )`;
}

function differingColumns(columns) {
  const pairs = columns
    .map((column) => `('${column}', kotlin.${column} IS DISTINCT FROM typescript.${column})`)
    .join(', ');
  return `ARRAY(SELECT column_name FROM (VALUES ${pairs}) AS pair(column_name, differs) WHERE differs)`;
}

function row(columns, side) {
  return `ROW(${columns.map((column) => `${side}.${column}`).join(', ')})`;
}

// One statement per table. `json_agg` over an ordered, limited subquery so the
// result is one parseable document rather than psql's array literal, which a
// value containing a comma or a brace would misparse.
export function diffSql(table, { maxDifferences }) {
  const diffed = DIFFED_TABLES.find((candidate) => candidate.table === table);
  if (diffed === undefined) throw new Error(`no diffed table named '${table}'`);
  if (!Number.isSafeInteger(maxDifferences) || maxDifferences < 1) {
    throw new Error(`maxDifferences must be a positive integer, got ${maxDifferences}`);
  }
  const columns = diffed.comparedForDiff;
  return `WITH ${sides(diffed, columns)}
          SELECT coalesce(json_agg(difference), '[]'::json) AS differences
          FROM (
            SELECT ${KEY_COLUMN} AS key,
                   kotlin.${KEY_COLUMN} IS NULL AS missing_in_kotlin,
                   typescript.${KEY_COLUMN} IS NULL AS missing_in_typescript,
                   ${differingColumns(columns)} AS columns
            FROM kotlin FULL OUTER JOIN typescript USING (${KEY_COLUMN})
            WHERE kotlin.${KEY_COLUMN} IS NULL
               OR typescript.${KEY_COLUMN} IS NULL
               OR ${row(columns, 'kotlin')} IS DISTINCT FROM ${row(columns, 'typescript')}
            ORDER BY ${KEY_COLUMN}
            LIMIT ${maxDifferences}
          ) difference`;
}

// The rejected alternative: the same comparison with every column cast to text.
// Exported only so a test can show that the jsonb pair `diffSql` tolerates
// would be reported here, which is the whole evidence for comparing as jsonb.
export function textDiffSql(table, columnNames) {
  const diffed = DIFFED_TABLES.find((candidate) => candidate.table === table);
  if (diffed === undefined) throw new Error(`no diffed table named '${table}'`);
  const unknown = columnNames.filter((column) => !diffed.comparedForDiff.includes(column));
  if (unknown.length > 0) throw new Error(`${table} does not compare column(s) ${unknown.join(', ')}`);
  const cast = columnNames.map((column) => `${column}::text AS ${column}`);
  return `WITH ${sides(diffed, cast)}
          SELECT coalesce(json_agg(${KEY_COLUMN}), '[]'::json) AS differences
          FROM (
            SELECT ${KEY_COLUMN}
            FROM kotlin FULL OUTER JOIN typescript USING (${KEY_COLUMN})
            WHERE kotlin.${KEY_COLUMN} IS NULL
               OR typescript.${KEY_COLUMN} IS NULL
               OR ${row(columnNames, 'kotlin')} IS DISTINCT FROM ${row(columnNames, 'typescript')}
            ORDER BY ${KEY_COLUMN}
          ) difference`;
}

// A FULL OUTER JOIN over a key that repeats is a cross join that reports
// nonsense, and campgrounds/campsites are unique on their natural key only
// among live rows (the index is partial on deleted_at IS NULL). A NULL key
// element is just as bad: array equality with a NULL element is NULL, so the
// row would silently match nothing. Both are checked before anything is
// trusted.
export function keyAuditSql(table, schema) {
  const diffed = DIFFED_TABLES.find((candidate) => candidate.table === table);
  if (diffed === undefined) throw new Error(`no diffed table named '${table}'`);
  return `WITH side AS (
            ${rowSelect(diffed, schema, diffed.comparedForDiff)}
          )
          SELECT coalesce(json_agg(audit), '[]'::json) AS problems
          FROM (
            SELECT ${KEY_COLUMN} AS key,
                   count(*) AS occurrences,
                   bool_or(array_position(${KEY_COLUMN}, NULL) IS NOT NULL) AS has_null_element
            FROM side
            GROUP BY ${KEY_COLUMN}
            HAVING count(*) > 1 OR bool_or(array_position(${KEY_COLUMN}, NULL) IS NOT NULL)
            ORDER BY ${KEY_COLUMN}
            LIMIT ${DUPLICATE_KEY_SAMPLE_LIMIT}
          ) audit`;
}

// Row count plus a digest of every compared value, so --prove-update-keys can
// show that blanking actually changed something. A blank step that blanked
// nothing would make the whole mode vacuous.
export function fingerprintSql(diffed, schema) {
  const columns = diffed.comparedForDiff;
  const digest = columns.map((column) => `coalesce(${column}::text, '')`).join(" || '|' || ");
  return `WITH side AS (
            ${rowSelect(diffed, schema, columns)}
          )
          SELECT count(*)::int AS rows,
                 coalesce(md5(string_agg(${digest}, '\\n' ORDER BY ${KEY_COLUMN})), '') AS fingerprint
          FROM side`;
}

export function rowCountSql(table, schema) {
  return `SELECT count(*)::int AS rows FROM ${quote(schema, table)}`;
}

// A table whose side select reaches fewer rows than the table holds has rows
// the diff cannot see at all, and this is not hypothetical: `pois` is reached
// only through its three link tables, so a poi written without its link row is
// invisible to the comparison on both sides. CampgroundRepo inserts the poi
// before the link and the link carries ON CONFLICT (campground_id) DO NOTHING,
// so a port that dedupes differently produces exactly that orphan — and the
// row-level diff would come back clean over it.
//
// With this holding, and the keys proved unique, every row of every table is on
// one side of the FULL OUTER JOIN, which is what makes a cross-side row-count
// comparison redundant rather than a second guard.
//
// `coveredBy` names the exemption when there is one: import_runs is append-only
// and its side select deliberately keeps only the newest run per source, so its
// covered count is lower by design and the run count it does not keep is
// compared as the derived `runs` column instead.
export function coverageSql(table, schema) {
  const diffed = DIFFED_TABLES.find((candidate) => candidate.table === table);
  if (diffed === undefined) throw new Error(`no diffed table named '${table}'`);
  return `WITH side AS (
            ${rowSelect(diffed, schema, diffed.comparedForDiff)}
          )
          SELECT (SELECT count(*)::int FROM ${quote(schema, table)}) AS present,
                 (SELECT count(*)::int FROM side) AS covered`;
}

// Every other base table the migrated schema holds. The completeness test
// holds `COPIED_TABLES ∪ this` against information_schema, so a migration that
// adds a table has to land in one list or the other — which is the same net as
// the per-column one, at table granularity. A table the catalog importer
// writes belongs in DIFFED_TABLES; anything else belongs here.
export const NOT_IMPORTED_TABLES = [
  'api_cache',
  'app_user',
  'availability',
  'availability_fetch_call',
  'availability_poller',
  'availability_run',
  'availability_watch',
  'availability_watch_poller',
  'availability_watch_target',
  'ingest_runs',
  'spatial_ref_sys',
  'user_booking_credentials',
  'user_identity',
  'user_role',
  'user_session',
  'user_settings',
  'vendor_rate_limit_bucket',
];

export const BASE_TABLES_SQL = `SELECT table_name AS name
  FROM information_schema.tables
  WHERE table_schema = '${TYPESCRIPT_SCHEMA}' AND table_type = 'BASE TABLE'
  ORDER BY table_name`;

// The tables whose side select must reach every row. import_runs is the one
// exemption and it is named here rather than inferred, so a future table that
// needs one has to say so.
export const COVERAGE_EXEMPT_TABLES = {
  import_runs: 'append-only; the side select keeps the newest run per source and compares the rest as `runs`',
};

// Blanks every compared column that is not part of the key, so each importer's
// pass 2 has to write it back from the captures. A column missing from one
// implementation's ON CONFLICT update set stays blank on that side alone and
// the diff names it. Generated from getTableConfig so it cannot drift from the
// schema; identity, key and ignored columns are left alone.
export function blankSql(diffed, schema) {
  const key = new Set(diffed.key);
  const assignments = diffed.columns
    .filter((column) => !Object.hasOwn(diffed.ignored, column.name) && !key.has(column.name))
    .map((column) => `${column.name} = ${blankValueFor(column, diffed.blankOverrides)}`);
  if (assignments.length === 0) return [];
  return [`UPDATE ${quote(schema, diffed.table)} SET ${assignments.join(', ')}`];
}
