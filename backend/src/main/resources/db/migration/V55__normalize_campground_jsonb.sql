-- Normalize the five typed campground JSONB columns into their canonical
-- keys. Every vendor but Campflare already wrote these keys. Campflare
-- rows carried upstream names (primary_phone, large_url, agency_name, ...),
-- which is why the read path used to carry per-vendor key fallbacks.
--
-- The read path now decodes these columns strictly, so this runs once over
-- stored rows. Idempotent: canonical rows map to themselves. source_payload
-- is untouched, so every upstream key the drawer renders is still served.
--
-- Text is trimmed and blanks become absent, which is what the old read path
-- did on the way out. Typed reads do not trim, so it happens once, here.

UPDATE campgrounds SET location = jsonb_strip_nulls(jsonb_build_object(
  'latitude',  location->'latitude',
  'longitude', location->'longitude',
  'region',    NULLIF(btrim(location->>'region'), ''),
  'country',   NULLIF(btrim(location->>'country'), ''),
  'elevation', location->'elevation',
  'directions', NULLIF(btrim(location->>'directions'), ''),
  'address',   CASE WHEN jsonb_typeof(location->'address') = 'object' THEN
    NULLIF(jsonb_strip_nulls(jsonb_build_object(
      'street',   NULLIF(btrim(COALESCE(location->'address'->>'street', location->'address'->>'street1', location->'address'->>'address_line')), ''),
      'city',     NULLIF(btrim(location->'address'->>'city'), ''),
      'state',    NULLIF(btrim(COALESCE(location->'address'->>'state', location->'address'->>'state_code')), ''),
      'postcode', NULLIF(btrim(COALESCE(location->'address'->>'postcode', location->'address'->>'postal_code', location->'address'->>'zipcode')), ''),
      'country',  NULLIF(btrim(COALESCE(location->'address'->>'country', location->'address'->>'country_code')), ''),
      'full',     NULLIF(btrim(location->'address'->>'full'), ''))), '{}'::jsonb)
  END))
WHERE jsonb_typeof(location) = 'object';

UPDATE campgrounds SET photos = COALESCE((
  SELECT jsonb_agg(jsonb_build_object('url', s.url) ORDER BY s.ord)
  FROM (
    SELECT p.ord, NULLIF(btrim(COALESCE(p.v->>'url', p.v->>'large_url', p.v->>'medium_url', p.v->>'small_url', p.v->>'original_url')), '') AS url
    FROM jsonb_array_elements(photos) WITH ORDINALITY AS p(v, ord)
    WHERE jsonb_typeof(p.v) = 'object'
  ) s WHERE s.url IS NOT NULL), '[]'::jsonb)
WHERE jsonb_typeof(photos) = 'array';

UPDATE campgrounds SET links = COALESCE((
  SELECT jsonb_agg(jsonb_strip_nulls(jsonb_build_object('url', s.url, 'title', s.title)) ORDER BY s.ord)
  FROM (
    SELECT l.ord,
           NULLIF(btrim(COALESCE(l.v->>'url', l.v->>'href')), '') AS url,
           NULLIF(btrim(COALESCE(l.v->>'title', l.v->>'label', l.v->>'name')), '') AS title
    FROM jsonb_array_elements(links) WITH ORDINALITY AS l(v, ord)
    WHERE jsonb_typeof(l.v) = 'object'
  ) s WHERE s.url IS NOT NULL), '[]'::jsonb)
WHERE jsonb_typeof(links) = 'array';

UPDATE campgrounds SET management = CASE
  WHEN NULLIF(btrim(COALESCE(management->>'agency', management->>'agency_name')), '') IS NULL THEN '{}'::jsonb
  ELSE jsonb_strip_nulls(jsonb_build_object(
    'agency',  NULLIF(btrim(COALESCE(management->>'agency', management->>'agency_name')), ''),
    'website', NULLIF(btrim(COALESCE(management->>'agency_website', management->>'website_url', management->>'website', management->>'url')), '')))
  END
WHERE jsonb_typeof(management) = 'object';

UPDATE campgrounds SET contact = jsonb_strip_nulls(jsonb_build_object(
  'phone', NULLIF(btrim(COALESCE(contact->>'phone', contact->>'primary_phone')), ''),
  'email', NULLIF(btrim(COALESCE(contact->>'email', contact->>'primary_email')), '')))
WHERE jsonb_typeof(contact) = 'object';
