-- Normalize the five typed campground JSONB columns into their canonical
-- keys. Every vendor but Campflare already wrote these keys. Campflare
-- rows carried upstream names (primary_phone, large_url, agency_name, ...),
-- which is why the read path used to carry per-vendor key fallbacks.
--
-- The read path now decodes these columns strictly, so this runs once over
-- stored rows. Idempotent: canonical rows map to themselves. source_payload
-- is untouched, so every upstream key the drawer renders is still served.

UPDATE campgrounds SET location = jsonb_strip_nulls(jsonb_build_object(
  'latitude',  location->'latitude',
  'longitude', location->'longitude',
  'region',    location->>'region',
  'country',   location->>'country',
  'elevation', location->'elevation',
  'address',   CASE WHEN jsonb_typeof(location->'address') = 'object' THEN
    NULLIF(jsonb_strip_nulls(jsonb_build_object(
      'street',   COALESCE(location->'address'->>'street', location->'address'->>'street1', location->'address'->>'address_line'),
      'city',     location->'address'->>'city',
      'state',    COALESCE(location->'address'->>'state', location->'address'->>'state_code'),
      'postcode', COALESCE(location->'address'->>'postcode', location->'address'->>'postal_code', location->'address'->>'zipcode'),
      'country',  COALESCE(location->'address'->>'country', location->'address'->>'country_code'))), '{}'::jsonb)
  END))
WHERE jsonb_typeof(location) = 'object';

UPDATE campgrounds SET photos = COALESCE((
  SELECT jsonb_agg(jsonb_build_object('url', s.url) ORDER BY s.ord)
  FROM (
    SELECT p.ord, COALESCE(p.v->>'url', p.v->>'large_url', p.v->>'medium_url', p.v->>'small_url', p.v->>'original_url') AS url
    FROM jsonb_array_elements(photos) WITH ORDINALITY AS p(v, ord)
    WHERE jsonb_typeof(p.v) = 'object'
  ) s WHERE s.url IS NOT NULL), '[]'::jsonb)
WHERE jsonb_typeof(photos) = 'array';

UPDATE campgrounds SET links = COALESCE((
  SELECT jsonb_agg(jsonb_strip_nulls(jsonb_build_object('url', s.url, 'title', s.title)) ORDER BY s.ord)
  FROM (
    SELECT l.ord,
           COALESCE(l.v->>'url', l.v->>'href') AS url,
           COALESCE(l.v->>'title', l.v->>'label', l.v->>'name') AS title
    FROM jsonb_array_elements(links) WITH ORDINALITY AS l(v, ord)
    WHERE jsonb_typeof(l.v) = 'object'
  ) s WHERE s.url IS NOT NULL), '[]'::jsonb)
WHERE jsonb_typeof(links) = 'array';

UPDATE campgrounds SET management = CASE
  WHEN COALESCE(management->>'agency', management->>'agency_name') IS NULL THEN '{}'::jsonb
  ELSE jsonb_strip_nulls(jsonb_build_object(
    'agency',  COALESCE(management->>'agency', management->>'agency_name'),
    'website', COALESCE(management->>'agency_website', management->>'website_url', management->>'website', management->>'url')))
  END
WHERE jsonb_typeof(management) = 'object';

UPDATE campgrounds SET contact = jsonb_strip_nulls(jsonb_build_object(
  'phone', COALESCE(contact->>'phone', contact->>'primary_phone'),
  'email', COALESCE(contact->>'email', contact->>'primary_email')))
WHERE jsonb_typeof(contact) = 'object';
