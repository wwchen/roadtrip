const NONE_EXPECTED = 'every natural key in the capture is distinct';
const ARRAY_ORDER =
  'the capture repeats ids; the payload is an array, both importers dedupe in document order with identically sized windows, and the diff is clean';

export const COLLISION_PINS = {
  'Campflare Campgrounds': { count: 0, reason: NONE_EXPECTED },
  'Campflare Campsites': {
    count: 566,
    reason: `566 of 300,344 campsite ids repeat (35 identical to the first, 531 not); 526 of them are Maryland state park sites the export lists under every campground of the park (shad-landing/milburn, manor-area-cunningham/cunningham-falls-0, patapsco-hollofield/hilton-patapsco), so the first-listed campground wins each; ${ARRAY_ORDER}`,
  },
  'Rec.gov Campgrounds': { count: 0, reason: NONE_EXPECTED },
  'Rec.gov Campsites': { count: 0, reason: NONE_EXPECTED },
  'Washington State Parks': { count: 0, reason: NONE_EXPECTED },
  'BC Provincial Parks': { count: 0, reason: NONE_EXPECTED },
  'Parks Canada': { count: 0, reason: NONE_EXPECTED },
  'Washington Aspira Resources': { count: 0, reason: NONE_EXPECTED },
  'BC Aspira Resources': { count: 0, reason: NONE_EXPECTED },
  'Parks Canada Aspira Resources': { count: 0, reason: NONE_EXPECTED },
  'Planet Fitness': { count: 0, reason: NONE_EXPECTED },
  'Alberta Provincial Parks': { count: 0, reason: NONE_EXPECTED },
  'New York State Parks': { count: 0, reason: NONE_EXPECTED },
  'Alberta Provincial Park Sites': { count: 0, reason: NONE_EXPECTED },
  'New York State Park Sites': { count: 0, reason: NONE_EXPECTED },
  'Tesla Superchargers': { count: 0, reason: NONE_EXPECTED },
  'California State Parks': { count: 0, reason: NONE_EXPECTED },
  'California State Park Sites': { count: 0, reason: NONE_EXPECTED },
};
