const NONE_EXPECTED = 'every natural key in the capture is distinct';
const ARRAY_ORDER =
  'the capture repeats ids; the payload is an array, both importers dedupe in document order with identically sized windows, and the diff is clean';

export const COLLISION_PINS = {
  'Campflare Campgrounds': { count: 0, reason: NONE_EXPECTED },
  'Campflare Campsites': {
    count: 40,
    reason: `40 of 299,818 campsite ids repeat under one campground (35 identical to the first, 5 not); a site the export lists under several campgrounds of one park keeps only the copy under the campground its loop name names, or the one named only for the park; ${ARRAY_ORDER}`,
  },
  'BC Provincial Parks': { count: 0, reason: NONE_EXPECTED },
  'Parks Canada': { count: 0, reason: NONE_EXPECTED },
  'BC Aspira Resources': { count: 0, reason: NONE_EXPECTED },
  'Parks Canada Aspira Resources': { count: 0, reason: NONE_EXPECTED },
  'Planet Fitness': { count: 0, reason: NONE_EXPECTED },
  'Alberta Provincial Parks': { count: 0, reason: NONE_EXPECTED },
  'Alberta Provincial Park Sites': { count: 0, reason: NONE_EXPECTED },
  'Tesla Superchargers': { count: 0, reason: NONE_EXPECTED },
};
