-- RFC 0007 / PR 2: seed governing_body and booking_provider dimension tables.
--
-- The slug column is the stable contract — Kotlin ETL transformers look
-- up FK ids by slug at import time. Adding a new agency or vendor =
-- inserting a row here, no code change.

-- governing_body: the agency that operates a POI. Distinct from
-- booking_provider because one agency can use multiple booking systems
-- and one booking platform serves many agencies.
INSERT INTO governing_body (slug, name, kind, country) VALUES
  -- US federal
  ('nps',    'National Park Service',                   'federal', 'US'),
  ('usfs',   'US Forest Service',                       'federal', 'US'),
  ('blm',    'Bureau of Land Management',               'federal', 'US'),
  ('coe',    'US Army Corps of Engineers',              'federal', 'US'),
  ('usfw',   'US Fish & Wildlife',                      'federal', 'US'),
  ('bor',    'Bureau of Reclamation',                   'federal', 'US'),
  ('tva',    'Tennessee Valley Authority',              'federal', 'US'),
  ('nm',     'National Monument',                       'federal', 'US'),
  ('nra',    'National Recreation Area',                'federal', 'US'),
  -- US state
  ('us-state-park',         'US State Parks',           'state',   'US'),
  ('us-state-rec',          'US State Recreation Area', 'state',   'US'),
  ('us-state-forest',       'US State Forest',          'state',   'US'),
  ('us-state-fishwildlife', 'US State Fish & Wildlife', 'state',   'US'),
  ('us-state-beach',        'US State Beach',           'state',   'US'),
  -- US local
  ('us-county',  'US County Park',     'local',   'US'),
  ('us-region',  'US Regional Park',   'local',   'US'),
  ('us-muni',   'US Municipal Park',   'local',   'US'),
  ('us-utility', 'US Utility Land',    'local',   'US'),
  -- US private
  ('us-private', 'US Private Campground', 'private', 'US'),
  -- Canada federal
  ('parks-canada', 'Parks Canada',                  'federal',    'CA'),
  -- Canada provincial
  ('bc-parks',     'BC Parks',                      'provincial', 'CA'),
  ('alberta-parks', 'Alberta Parks',                'provincial', 'CA'),
  -- Corporate (no agency — operator IS the corporation)
  ('tesla', 'Tesla',                  'corporate', NULL),
  ('pf',    'Planet Fitness Corp',    'corporate', 'US')
;

-- booking_provider: the reservation system. Aspira gets 3 rows because
-- the same adapter speaks to 3 hosts (PC/BC/WA) — the FK on a POI row
-- selects which host to call. `none` is the implicit fallback for pins
-- that aren't reservable through any provider we integrate with.
INSERT INTO booking_provider (vendor, name, host, adapter_class) VALUES
  -- Recreation.gov: single host, single adapter.
  ('recgov', 'Recreation.gov',                'www.recreation.gov',           'RecGovAdapter'),
  -- Aspira NextGen: same Kotlin adapter, different host per row.
  ('aspira', 'Aspira NextGen (Parks Canada)', 'reservation.pc.gc.ca',          'AspiraAdapter'),
  ('aspira', 'Aspira NextGen (BC Parks)',     'camping.bcparks.ca',            'AspiraAdapter'),
  ('aspira', 'Aspira NextGen (WA State Parks)', 'washington.goingtocamp.com',  'AspiraAdapter'),
  -- Camis (Alberta Parks). No adapter implemented yet — the row exists so
  -- curated AB data can FK to it; availability returns no_provider until
  -- an adapter ships.
  ('camis',  'Camis (Alberta Parks)',         'reserve.albertaparks.ca',       '')
;
