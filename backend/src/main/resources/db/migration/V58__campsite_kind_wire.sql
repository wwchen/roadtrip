-- campsites.kind moves from each vendor's own type string to the CampsiteKind
-- wire vocabulary. The CASE expressions below mirror
-- service/etl/framework/CampsiteKinds.kt one row at a time; anything the table
-- cannot classify becomes 'other' and the next import reclassifies it.
--
-- Every UPDATE skips rows whose kind is already a wire value, so the script is
-- idempotent and a row the typed repo just wrote is left alone.

-- A row no later import touches would lose the vendor's word entirely, so keep it
-- in kind_listed before the rewrite.
UPDATE campsites SET kind_listed = kind
WHERE kind_listed IS NULL
  AND kind NOT IN ('standard', 'tent', 'rv', 'cabin', 'group', 'walk_in', 'boat_in',
                   'equestrian', 'backcountry', 'day_use', 'other');

UPDATE campsites SET kind = CASE
    WHEN upper(btrim(kind)) ~ '^GROUP( |$)' THEN 'group'
    WHEN upper(btrim(kind)) ~ '^TENT ONLY( |$)' THEN 'tent'
    WHEN upper(btrim(kind)) ~ '^RV( |$)' THEN 'rv'
    WHEN upper(btrim(kind)) ~ '^(CABIN|YURT|LOOKOUT|OVERNIGHT SHELTER|SHELTER)( |$)' THEN 'cabin'
    WHEN upper(btrim(kind)) ~ '^(WALK TO|HIKE TO)( |$)' THEN 'walk_in'
    WHEN upper(btrim(kind)) ~ '^(BOAT IN|MOORING|ANCHORAGE)( |$)' THEN 'boat_in'
    WHEN upper(btrim(kind)) ~ '^EQUESTRIAN( |$)' THEN 'equestrian'
    WHEN upper(btrim(kind)) ~ '^STANDARD( |$)' THEN 'standard'
    WHEN upper(btrim(kind)) ~ '^ZONE( |$)' THEN 'backcountry'
    WHEN upper(btrim(kind)) ~ '^(PICNIC|PARKING|DAY USE)( |$)' THEN 'day_use'
    ELSE 'other'
  END
WHERE data_provider = 'recgov'
  AND kind NOT IN ('standard', 'tent', 'rv', 'cabin', 'group', 'walk_in', 'boat_in',
                   'equestrian', 'backcountry', 'day_use', 'other');

UPDATE campsites SET kind = CASE btrim(kind)
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
WHERE data_provider = 'campflare'
  AND kind NOT IN ('standard', 'tent', 'rv', 'cabin', 'group', 'walk_in', 'boat_in',
                   'equestrian', 'backcountry', 'day_use', 'other');

-- Aspira dictionary names, exact names before prefixes; 'bcparks-strapi' is the
-- legacy provider value some deployed Aspira campsite rows still carry.
UPDATE campsites SET kind = CASE
    WHEN btrim(kind) IN ('Campsite', 'Campsite/Seasonal', 'Overflow') THEN 'standard'
    WHEN btrim(kind) IN ('Cabin', 'Rustic Cabin', 'Deluxe Cabin', 'Backcountry Cabin', 'Yurt',
                         'oTENTik', 'Ôasis', 'MicrOcube', 'Teepee', 'Prospector Tent',
                         'Platform Tent', 'Adirondack', 'Equipped Camping', 'Vacation House') THEN 'cabin'
    WHEN btrim(kind) = 'Equestrian' THEN 'equestrian'
    WHEN btrim(kind) IN ('Marina', 'Mooring Buoy', 'Marine Trail', 'Annual Marina') THEN 'boat_in'
    WHEN btrim(kind) IN ('Daily Fishing', 'Guided Event', 'Hiking Trip', 'Ferry') THEN 'day_use'
    WHEN btrim(kind) LIKE 'Backcountry%' OR btrim(kind) LIKE 'Wilderness%' THEN 'backcountry'
    WHEN btrim(kind) LIKE 'Group%' THEN 'group'
    WHEN btrim(kind) LIKE 'Day Use%' OR btrim(kind) LIKE 'Conference%' OR btrim(kind) LIKE 'Retreat%' THEN 'day_use'
    ELSE 'other'
  END
WHERE data_provider IN ('aspira', 'bcparks-strapi')
  AND kind NOT IN ('standard', 'tent', 'rv', 'cabin', 'group', 'walk_in', 'boat_in',
                   'equestrian', 'backcountry', 'day_use', 'other');

-- ReserveCalifornia unit-type names are free text, matched by substring in the
-- order CampsiteKinds.reserveCalifornia uses; the first branch that hits wins.
UPDATE campsites SET kind = CASE
    WHEN lower(btrim(kind)) LIKE '%day use%' OR lower(btrim(kind)) LIKE '%dailyuse%' THEN 'day_use'
    WHEN lower(btrim(kind)) LIKE '%group%' THEN 'group'
    WHEN lower(btrim(kind)) LIKE '%equestrian%' OR lower(btrim(kind)) LIKE '%equestrain%' THEN 'equestrian'
    WHEN lower(btrim(kind)) LIKE '%cabin%' OR lower(btrim(kind)) LIKE '%cottage%'
         OR lower(btrim(kind)) LIKE '%yurt%' THEN 'cabin'
    WHEN lower(btrim(kind)) LIKE '%boat in%' OR lower(btrim(kind)) LIKE '%floating camp%' THEN 'boat_in'
    WHEN lower(btrim(kind)) LIKE '%hike%' OR lower(btrim(kind)) LIKE '%bike%'
         OR lower(btrim(kind)) LIKE '%walk-in%' THEN 'walk_in'
    WHEN lower(btrim(kind)) LIKE '%hook up%' THEN 'rv'
    WHEN lower(btrim(kind)) LIKE '%tent%' THEN 'tent'
    WHEN lower(btrim(kind)) LIKE '%campsite%' THEN 'standard'
    ELSE 'other'
  END
WHERE data_provider = 'reservecalifornia'
  AND kind NOT IN ('standard', 'tent', 'rv', 'cabin', 'group', 'walk_in', 'boat_in',
                   'equestrian', 'backcountry', 'day_use', 'other');

-- ReserveAmerica's calendar carries no upstream site type at all.
UPDATE campsites SET kind = 'other'
WHERE data_provider = 'reserveamerica'
  AND kind NOT IN ('standard', 'tent', 'rv', 'cabin', 'group', 'walk_in', 'boat_in',
                   'equestrian', 'backcountry', 'day_use', 'other');

-- Anything left unclassified (a provider retired before this migration) is 'other',
-- so the column is wholly in the wire vocabulary and the read path can decode strictly.
UPDATE campsites SET kind = 'other'
WHERE kind NOT IN ('standard', 'tent', 'rv', 'cabin', 'group', 'walk_in', 'boat_in',
                   'equestrian', 'backcountry', 'day_use', 'other');

-- Dropped first so a replay of this script is idempotent.
ALTER TABLE campsites DROP CONSTRAINT IF EXISTS campsites_kind_wire_check;
ALTER TABLE campsites ADD CONSTRAINT campsites_kind_wire_check
  CHECK (kind IN ('standard', 'tent', 'rv', 'cabin', 'group', 'walk_in', 'boat_in',
                  'equestrian', 'backcountry', 'day_use', 'other'));

-- Stored watch filters are not tied to one provider, so this CASE is the union of the
-- tables above, over both shapes the parser accepts (a bare string and an array).
UPDATE availability_watch w
SET campsite_filters = jsonb_set(
      w.campsite_filters,
      '{site_type}',
      CASE WHEN jsonb_typeof(w.campsite_filters -> 'site_type') = 'string'
           THEN m.mapped -> 0
           ELSE m.mapped END)
FROM (
  SELECT s.id,
         jsonb_agg(to_jsonb(CASE
           WHEN s.value IN ('standard', 'tent', 'rv', 'cabin', 'group', 'walk_in', 'boat_in',
                            'equestrian', 'backcountry', 'day_use', 'other') THEN s.value
           -- Checked first so ReserveCalifornia's "Group Day Use" beats recgov's GROUP prefix below.
           WHEN lower(btrim(s.value)) LIKE '%day use%' OR lower(btrim(s.value)) LIKE '%dailyuse%' THEN 'day_use'
           WHEN lower(btrim(s.value)) LIKE '%walk-in%' THEN 'walk_in'
           WHEN upper(btrim(s.value)) ~ '^GROUP( |$)' THEN 'group'
           WHEN upper(btrim(s.value)) ~ '^TENT ONLY( |$)' THEN 'tent'
           WHEN upper(btrim(s.value)) ~ '^RV( |$)' THEN 'rv'
           WHEN upper(btrim(s.value)) ~ '^(CABIN|YURT|LOOKOUT|OVERNIGHT SHELTER|SHELTER)( |$)' THEN 'cabin'
           WHEN upper(btrim(s.value)) ~ '^(WALK TO|HIKE TO)( |$)' THEN 'walk_in'
           WHEN upper(btrim(s.value)) ~ '^(BOAT IN|MOORING|ANCHORAGE)( |$)' THEN 'boat_in'
           WHEN lower(btrim(s.value)) LIKE '%group%' THEN 'group'
           WHEN upper(btrim(s.value)) ~ '^EQUESTRIAN( |$)' THEN 'equestrian'
           WHEN upper(btrim(s.value)) ~ '^STANDARD( |$)' THEN 'standard'
           WHEN upper(btrim(s.value)) ~ '^ZONE( |$)' THEN 'backcountry'
           WHEN upper(btrim(s.value)) ~ '^(PICNIC|PARKING|DAY USE)( |$)' THEN 'day_use'
           WHEN btrim(s.value) = 'tent-only' THEN 'tent'
           WHEN btrim(s.value) = 'walk-to' THEN 'walk_in'
           WHEN btrim(s.value) = 'water-access' THEN 'boat_in'
           WHEN btrim(s.value) IN ('Campsite', 'Campsite/Seasonal', 'Overflow') THEN 'standard'
           WHEN btrim(s.value) IN ('Cabin', 'Rustic Cabin', 'Deluxe Cabin', 'Backcountry Cabin', 'Yurt',
                                   'oTENTik', 'Ôasis', 'MicrOcube', 'Teepee', 'Prospector Tent',
                                   'Platform Tent', 'Adirondack', 'Equipped Camping', 'Vacation House') THEN 'cabin'
           WHEN btrim(s.value) IN ('Marina', 'Mooring Buoy', 'Marine Trail', 'Annual Marina') THEN 'boat_in'
           WHEN btrim(s.value) IN ('Daily Fishing', 'Guided Event', 'Hiking Trip', 'Ferry') THEN 'day_use'
           WHEN btrim(s.value) LIKE 'Backcountry%' OR btrim(s.value) LIKE 'Wilderness%' THEN 'backcountry'
           WHEN btrim(s.value) LIKE 'Group%' THEN 'group'
           WHEN btrim(s.value) LIKE 'Conference%' OR btrim(s.value) LIKE 'Retreat%' THEN 'day_use'
           WHEN lower(btrim(s.value)) LIKE '%equestrian%' OR lower(btrim(s.value)) LIKE '%equestrain%' THEN 'equestrian'
           WHEN lower(btrim(s.value)) LIKE '%cabin%' OR lower(btrim(s.value)) LIKE '%cottage%'
                OR lower(btrim(s.value)) LIKE '%yurt%' THEN 'cabin'
           WHEN lower(btrim(s.value)) LIKE '%boat in%' OR lower(btrim(s.value)) LIKE '%floating camp%' THEN 'boat_in'
           WHEN lower(btrim(s.value)) LIKE '%hike%' OR lower(btrim(s.value)) LIKE '%bike%'
                OR lower(btrim(s.value)) LIKE '%walk-in%' THEN 'walk_in'
           WHEN lower(btrim(s.value)) LIKE '%hook up%' THEN 'rv'
           WHEN lower(btrim(s.value)) LIKE '%tent%' THEN 'tent'
           WHEN lower(btrim(s.value)) LIKE '%campsite%' THEN 'standard'
           ELSE 'other'
         END) ORDER BY s.ord) AS mapped
  FROM (
    SELECT w2.id, e.value, e.ord
    FROM availability_watch w2,
         LATERAL jsonb_array_elements_text(
           CASE WHEN jsonb_typeof(w2.campsite_filters -> 'site_type') = 'string'
                THEN jsonb_build_array(w2.campsite_filters -> 'site_type')
                ELSE w2.campsite_filters -> 'site_type' END
         ) WITH ORDINALITY AS e(value, ord)
    WHERE jsonb_typeof(w2.campsite_filters -> 'site_type') IN ('string', 'array')
  ) s
  GROUP BY s.id
) m
WHERE w.id = m.id;
