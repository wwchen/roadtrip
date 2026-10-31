const NONE_EXPECTED = 'every natural key in the capture is distinct';
const ARRAY_ORDER =
  'the capture repeats ids; the payload is an array, both importers dedupe in document order with identically sized windows, and the diff is clean';

export const COLLISION_PINS = {
  'Campflare Campgrounds': { count: 0, reason: NONE_EXPECTED },
  'Campflare Campsites': {
    count: 40,
    reason: `40 of 299,904 campsite ids repeat (35 identical to the first, 5 not); ${ARRAY_ORDER}`,
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
