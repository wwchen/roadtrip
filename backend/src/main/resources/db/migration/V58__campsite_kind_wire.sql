-- campsites.kind moves from each vendor's own type string to the CampsiteKind
-- wire vocabulary. campsite_wire_kind() mirrors
-- service/etl/framework/CampsiteKinds.kt one vendor at a time; anything it
-- cannot classify becomes 'other' and the next import reclassifies it. The
-- function is dropped at the end of this script.
--
-- Every UPDATE skips rows whose kind is already a wire value, so the script is
-- idempotent and a row the typed repo just wrote is left alone.

-- One expression for both uses below, so they cannot drift; the last arm is the
-- vendor-agnostic union, for a value whose provider is unknown.
CREATE OR REPLACE FUNCTION campsite_wire_kind(provider text, raw text) RETURNS text
LANGUAGE sql IMMUTABLE AS $fn$
  SELECT CASE
    WHEN raw IN ('standard', 'tent', 'rv', 'cabin', 'group', 'walk_in', 'boat_in',
                 'equestrian', 'backcountry', 'day_use', 'other') THEN raw
    WHEN provider = 'recgov' THEN CASE
        WHEN upper(btrim(raw)) ~ '^GROUP( |$)' THEN 'group'
        WHEN upper(btrim(raw)) ~ '^TENT ONLY( |$)' THEN 'tent'
        WHEN upper(btrim(raw)) ~ '^RV( |$)' THEN 'rv'
        WHEN upper(btrim(raw)) ~ '^(CABIN|YURT|LOOKOUT|OVERNIGHT SHELTER|SHELTER)( |$)' THEN 'cabin'
        WHEN upper(btrim(raw)) ~ '^(WALK TO|HIKE TO)( |$)' THEN 'walk_in'
        WHEN upper(btrim(raw)) ~ '^(BOAT IN|MOORING|ANCHORAGE)( |$)' THEN 'boat_in'
        WHEN upper(btrim(raw)) ~ '^EQUESTRIAN( |$)' THEN 'equestrian'
        WHEN upper(btrim(raw)) ~ '^STANDARD( |$)' THEN 'standard'
        WHEN upper(btrim(raw)) ~ '^ZONE( |$)' THEN 'backcountry'
        WHEN upper(btrim(raw)) ~ '^(PICNIC|PARKING|DAY USE)( |$)' THEN 'day_use'
        ELSE 'other'
      END
    WHEN provider = 'campflare' THEN CASE btrim(raw)
        WHEN 'standard' THEN 'standard'
        WHEN 'tent-only' THEN 'tent'
        WHEN 'rv' THEN 'rv'
        WHEN 'cabin' THEN 'cabin'
        WHEN 'group' THEN 'group'
        WHEN 'walk-to' THEN 'walk_in'
        WHEN 'water-access' THEN 'boat_in'
        WHEN 'equestrian' THEN 'equestrian'
        ELSE 'other'
      END
    WHEN provider IN ('aspira', 'bcparks-strapi') THEN CASE
        WHEN btrim(raw) IN ('Campsite', 'Campsite/Seasonal', 'Overflow') THEN 'standard'
        WHEN btrim(raw) IN ('Cabin', 'Rustic Cabin', 'Deluxe Cabin', 'Backcountry Cabin', 'Yurt',
                            'oTENTik', 'Ôasis', 'MicrOcube', 'Teepee', 'Prospector Tent',
                            'Platform Tent', 'Adirondack', 'Equipped Camping', 'Vacation House') THEN 'cabin'
        WHEN btrim(raw) = 'Equestrian' THEN 'equestrian'
        WHEN btrim(raw) IN ('Marina', 'Mooring Buoy', 'Marine Trail', 'Annual Marina') THEN 'boat_in'
        WHEN btrim(raw) IN ('Daily Fishing', 'Guided Event', 'Hiking Trip', 'Ferry') THEN 'day_use'
        WHEN btrim(raw) LIKE 'Backcountry%' OR btrim(raw) LIKE 'Wilderness%' THEN 'backcountry'
        WHEN btrim(raw) LIKE 'Group%' THEN 'group'
        WHEN btrim(raw) LIKE 'Day Use%' OR btrim(raw) LIKE 'Conference%' OR btrim(raw) LIKE 'Retreat%' THEN 'day_use'
        ELSE 'other'
      END
    WHEN provider = 'reservecalifornia' THEN CASE
        WHEN lower(btrim(raw)) LIKE '%day use%' OR lower(btrim(raw)) LIKE '%dailyuse%' THEN 'day_use'
        WHEN lower(btrim(raw)) LIKE '%group%' THEN 'group'
        WHEN lower(btrim(raw)) LIKE '%equestrian%' OR lower(btrim(raw)) LIKE '%equestrain%' THEN 'equestrian'
        WHEN lower(btrim(raw)) LIKE '%cabin%' OR lower(btrim(raw)) LIKE '%cottage%'
             OR lower(btrim(raw)) LIKE '%yurt%' THEN 'cabin'
        WHEN lower(btrim(raw)) LIKE '%boat in%' OR lower(btrim(raw)) LIKE '%floating camp%' THEN 'boat_in'
        WHEN lower(btrim(raw)) LIKE '%hike%' OR lower(btrim(raw)) LIKE '%bike%'
             OR lower(btrim(raw)) LIKE '%walk-in%' THEN 'walk_in'
        WHEN lower(btrim(raw)) LIKE '%hook up%' THEN 'rv'
        WHEN lower(btrim(raw)) LIKE '%tent%' THEN 'tent'
        WHEN lower(btrim(raw)) LIKE '%campsite%' THEN 'standard'
        ELSE 'other'
      END
    WHEN provider = 'reserveamerica' THEN 'other'
    ELSE CASE
        WHEN lower(btrim(raw)) LIKE '%day use%' OR lower(btrim(raw)) LIKE '%dailyuse%' THEN 'day_use'
        WHEN lower(btrim(raw)) LIKE '%group%' THEN 'group'
        WHEN lower(btrim(raw)) LIKE '%walk-in%' THEN 'walk_in'
        WHEN upper(btrim(raw)) ~ '^TENT ONLY( |$)' THEN 'tent'
        WHEN upper(btrim(raw)) ~ '^RV( |$)' THEN 'rv'
        WHEN upper(btrim(raw)) ~ '^(CABIN|YURT|LOOKOUT|OVERNIGHT SHELTER|SHELTER)( |$)' THEN 'cabin'
        WHEN upper(btrim(raw)) ~ '^(WALK TO|HIKE TO)( |$)' THEN 'walk_in'
        WHEN upper(btrim(raw)) ~ '^(BOAT IN|MOORING|ANCHORAGE)( |$)' THEN 'boat_in'
        WHEN upper(btrim(raw)) ~ '^EQUESTRIAN( |$)' THEN 'equestrian'
        WHEN upper(btrim(raw)) ~ '^STANDARD( |$)' THEN 'standard'
        WHEN upper(btrim(raw)) ~ '^ZONE( |$)' THEN 'backcountry'
        WHEN upper(btrim(raw)) ~ '^(PICNIC|PARKING|DAY USE)( |$)' THEN 'day_use'
        WHEN btrim(raw) = 'tent-only' THEN 'tent'
        WHEN btrim(raw) = 'walk-to' THEN 'walk_in'
        WHEN btrim(raw) = 'water-access' THEN 'boat_in'
        WHEN btrim(raw) IN ('Campsite', 'Campsite/Seasonal', 'Overflow') THEN 'standard'
        WHEN btrim(raw) IN ('Cabin', 'Rustic Cabin', 'Deluxe Cabin', 'Backcountry Cabin', 'Yurt',
                            'oTENTik', 'Ôasis', 'MicrOcube', 'Teepee', 'Prospector Tent',
                            'Platform Tent', 'Adirondack', 'Equipped Camping', 'Vacation House') THEN 'cabin'
        WHEN btrim(raw) IN ('Marina', 'Mooring Buoy', 'Marine Trail', 'Annual Marina') THEN 'boat_in'
        WHEN btrim(raw) IN ('Daily Fishing', 'Guided Event', 'Hiking Trip', 'Ferry') THEN 'day_use'
        WHEN btrim(raw) LIKE 'Backcountry%' OR btrim(raw) LIKE 'Wilderness%' THEN 'backcountry'
        WHEN btrim(raw) LIKE 'Conference%' OR btrim(raw) LIKE 'Retreat%' THEN 'day_use'
        WHEN lower(btrim(raw)) LIKE '%equestrian%' OR lower(btrim(raw)) LIKE '%equestrain%' THEN 'equestrian'
        WHEN lower(btrim(raw)) LIKE '%cabin%' OR lower(btrim(raw)) LIKE '%cottage%'
             OR lower(btrim(raw)) LIKE '%yurt%' THEN 'cabin'
        WHEN lower(btrim(raw)) LIKE '%boat in%' OR lower(btrim(raw)) LIKE '%floating camp%' THEN 'boat_in'
        WHEN lower(btrim(raw)) LIKE '%hike%' OR lower(btrim(raw)) LIKE '%bike%' THEN 'walk_in'
        WHEN lower(btrim(raw)) LIKE '%hook up%' THEN 'rv'
        WHEN lower(btrim(raw)) LIKE '%tent%' THEN 'tent'
        WHEN lower(btrim(raw)) LIKE '%campsite%' THEN 'standard'
        ELSE 'other'
      END
  END
$fn$;

-- A row no later import touches would lose the vendor's word entirely, so keep it
-- in kind_listed before the rewrite.
UPDATE campsites SET kind_listed = kind
WHERE kind_listed IS NULL
  AND kind NOT IN ('standard', 'tent', 'rv', 'cabin', 'group', 'walk_in', 'boat_in',
                   'equestrian', 'backcountry', 'day_use', 'other');

UPDATE campsites SET kind = campsite_wire_kind(data_provider, kind)
WHERE kind NOT IN ('standard', 'tent', 'rv', 'cabin', 'group', 'walk_in', 'boat_in',
                   'equestrian', 'backcountry', 'day_use', 'other');

-- Dropped first so a replay of this script is idempotent, and added NOT VALID so
-- the validating scan runs without ACCESS EXCLUSIVE.
ALTER TABLE campsites DROP CONSTRAINT IF EXISTS campsites_kind_wire_check;
ALTER TABLE campsites ADD CONSTRAINT campsites_kind_wire_check
  CHECK (kind IN ('standard', 'tent', 'rv', 'cabin', 'group', 'walk_in', 'boat_in',
                  'equestrian', 'backcountry', 'day_use', 'other')) NOT VALID;
ALTER TABLE campsites VALIDATE CONSTRAINT campsites_kind_wire_check;

-- A campsite target names its own provider, a POI target its campground's; targets
-- that disagree, or resolve none, fall through to the union arm above.
WITH watch_provider AS (
  SELECT w2.id,
         CASE WHEN count(DISTINCT COALESCE(cs.data_provider, cg.data_provider)) = 1
              THEN min(COALESCE(cs.data_provider, cg.data_provider)) END AS provider
  FROM availability_watch w2
  LEFT JOIN availability_watch_target t ON t.watch_id = w2.id
  LEFT JOIN campsites cs ON cs.id = t.campsite_id
  LEFT JOIN poi_campgrounds pc ON pc.poi_id = t.poi_id
  LEFT JOIN campgrounds cg ON cg.id = pc.campground_id
  GROUP BY w2.id
), site_type_value AS (
  SELECT w2.id, p.provider, e.value, e.ord
  FROM availability_watch w2
  JOIN watch_provider p ON p.id = w2.id,
       LATERAL jsonb_array_elements_text(
         CASE WHEN jsonb_typeof(w2.campsite_filters -> 'site_type') = 'string'
              THEN jsonb_build_array(w2.campsite_filters -> 'site_type')
              ELSE w2.campsite_filters -> 'site_type' END
       ) WITH ORDINALITY AS e(value, ord)
  WHERE jsonb_typeof(w2.campsite_filters -> 'site_type') IN ('string', 'array')
), mapped_site_type AS (
  SELECT id, jsonb_agg(to_jsonb(campsite_wire_kind(provider, value)) ORDER BY ord) AS mapped
  FROM site_type_value
  GROUP BY id
)
UPDATE availability_watch w
SET campsite_filters = jsonb_set(
      w.campsite_filters,
      '{site_type}',
      CASE WHEN jsonb_typeof(w.campsite_filters -> 'site_type') = 'string'
           THEN m.mapped -> 0
           ELSE m.mapped END)
FROM mapped_site_type m
WHERE w.id = m.id;

DROP FUNCTION IF EXISTS campsite_wire_kind(text, text);
