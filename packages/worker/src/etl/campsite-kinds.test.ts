import { CampsiteKind } from '@roadtrip/domain';
import { describe, expect, test } from 'vitest';
import {
  campsiteKindAspira,
  campsiteKindCampflare,
  campsiteKindRecGov,
  campsiteKindReserveCalifornia,
} from './campsite-kinds.js';

type Cases = Record<string, CampsiteKind>;

function classify(cases: Cases, kindOf: (input: string) => CampsiteKind): Cases {
  return Object.fromEntries(Object.keys(cases).map((input) => [input, kindOf(input)]));
}

describe('rec.gov', () => {
  test('campsite types map by leading token', () => {
    const expected: Cases = {
      'STANDARD NONELECTRIC': CampsiteKind.STANDARD,
      'STANDARD ELECTRIC': CampsiteKind.STANDARD,
      'TENT ONLY NONELECTRIC': CampsiteKind.TENT,
      'RV NONELECTRIC': CampsiteKind.RV,
      'RV ELECTRIC': CampsiteKind.RV,
      'WALK TO': CampsiteKind.WALK_IN,
      'HIKE TO': CampsiteKind.WALK_IN,
      'GROUP STANDARD NONELECTRIC': CampsiteKind.GROUP,
      'GROUP TENT ONLY AREA NONELECTRIC': CampsiteKind.GROUP,
      'GROUP SHELTER NONELECTRIC': CampsiteKind.GROUP,
      'GROUP PICNIC AREA': CampsiteKind.GROUP,
      'EQUESTRIAN NONELECTRIC': CampsiteKind.EQUESTRIAN,
      'CABIN NONELECTRIC': CampsiteKind.CABIN,
      'CABIN ELECTRIC': CampsiteKind.CABIN,
      'SHELTER NONELECTRIC': CampsiteKind.CABIN,
      'OVERNIGHT SHELTER NONELECTRIC': CampsiteKind.CABIN,
      LOOKOUT: CampsiteKind.CABIN,
      YURT: CampsiteKind.CABIN,
      'BOAT IN': CampsiteKind.BOAT_IN,
      MOORING: CampsiteKind.BOAT_IN,
      ANCHORAGE: CampsiteKind.BOAT_IN,
      PICNIC: CampsiteKind.DAY_USE,
      PARKING: CampsiteKind.DAY_USE,
      'DAY USE': CampsiteKind.DAY_USE,
      ZONE: CampsiteKind.BACKCOUNTRY,
    };
    expect(classify(expected, (input) => campsiteKindRecGov(input).kind)).toEqual(expected);
  });

  test('unclassifiable campsite types fall back to other', () => {
    const unclassifiable = [null, undefined, '', '   ', 'MANAGEMENT', 'SOMETHING NEW NONELECTRIC'];
    expect(unclassifiable.map((input) => campsiteKindRecGov(input).kind)).toEqual(
      unclassifiable.map(() => CampsiteKind.OTHER),
    );
  });

  test('a phrase must match whole leading tokens, not a string prefix', () => {
    expect(campsiteKindRecGov('RVS NONELECTRIC').kind).toBe(CampsiteKind.OTHER);
    expect(campsiteKindRecGov('TENT').kind).toBe(CampsiteKind.OTHER);
    expect(campsiteKindRecGov('NONELECTRIC TENT ONLY').kind).toBe(CampsiteKind.OTHER);
  });

  test('mixed-case input matches the same as upper case', () => {
    expect(campsiteKindRecGov('Tent Only Nonelectric')).toEqual({ kind: CampsiteKind.TENT, electric: false });
  });

  test('electric hookups come off the trailing token', () => {
    expect(campsiteKindRecGov('STANDARD ELECTRIC').electric).toBe(true);
    expect(campsiteKindRecGov('RV ELECTRIC').electric).toBe(true);
    expect(campsiteKindRecGov('STANDARD NONELECTRIC').electric).toBe(false);
    expect(campsiteKindRecGov('TENT ONLY NONELECTRIC').electric).toBe(false);
    expect(campsiteKindRecGov('WALK TO').electric).toBeNull();
    expect(campsiteKindRecGov('GROUP PICNIC AREA').electric).toBeNull();
    expect(campsiteKindRecGov(null).electric).toBeNull();
  });

  test('an unclassifiable type still reports its trailing hookup token', () => {
    expect(campsiteKindRecGov('SOMETHING NEW NONELECTRIC')).toEqual({ kind: CampsiteKind.OTHER, electric: false });
    expect(campsiteKindRecGov('SOMETHING NEW ELECTRIC')).toEqual({ kind: CampsiteKind.OTHER, electric: true });
  });
});

describe('campflare', () => {
  test('kinds map by exact value', () => {
    const expected: Cases = {
      standard: CampsiteKind.STANDARD,
      'tent-only': CampsiteKind.TENT,
      rv: CampsiteKind.RV,
      cabin: CampsiteKind.CABIN,
      group: CampsiteKind.GROUP,
      'walk-to': CampsiteKind.WALK_IN,
      'water-access': CampsiteKind.BOAT_IN,
      equestrian: CampsiteKind.EQUESTRIAN,
      management: CampsiteKind.OTHER,
    };
    expect(classify(expected, campsiteKindCampflare)).toEqual(expected);
  });

  test('unknown kinds fall back to other, including names Object.prototype owns', () => {
    const unclassifiable = [null, undefined, '', 'STANDARD', 'houseboat', 'constructor', 'toString', '__proto__'];
    expect(unclassifiable.map(campsiteKindCampflare)).toEqual(unclassifiable.map(() => CampsiteKind.OTHER));
  });

  test('surrounding whitespace is trimmed before the lookup', () => {
    expect(campsiteKindCampflare('  tent-only ')).toBe(CampsiteKind.TENT);
  });
});

describe('aspira', () => {
  test('dictionary names map by exact name, then by prefix', () => {
    const expected: Cases = {
      Campsite: CampsiteKind.STANDARD,
      'Campsite/Seasonal': CampsiteKind.STANDARD,
      Overflow: CampsiteKind.STANDARD,
      Cabin: CampsiteKind.CABIN,
      'Rustic Cabin': CampsiteKind.CABIN,
      'Deluxe Cabin': CampsiteKind.CABIN,
      'Backcountry Cabin': CampsiteKind.CABIN,
      Yurt: CampsiteKind.CABIN,
      oTENTik: CampsiteKind.CABIN,
      Ôasis: CampsiteKind.CABIN,
      MicrOcube: CampsiteKind.CABIN,
      Teepee: CampsiteKind.CABIN,
      'Prospector Tent': CampsiteKind.CABIN,
      'Platform Tent': CampsiteKind.CABIN,
      Adirondack: CampsiteKind.CABIN,
      'Equipped Camping': CampsiteKind.CABIN,
      'Vacation House': CampsiteKind.CABIN,
      'Backcountry Site': CampsiteKind.BACKCOUNTRY,
      'Backcountry Zone': CampsiteKind.BACKCOUNTRY,
      'Wilderness Camping': CampsiteKind.BACKCOUNTRY,
      'Group Campground': CampsiteKind.GROUP,
      'Group Camp': CampsiteKind.GROUP,
      Equestrian: CampsiteKind.EQUESTRIAN,
      Marina: CampsiteKind.BOAT_IN,
      'Mooring Buoy': CampsiteKind.BOAT_IN,
      'Marine Trail': CampsiteKind.BOAT_IN,
      'Annual Marina': CampsiteKind.BOAT_IN,
      'Day Use Facility': CampsiteKind.DAY_USE,
      'Conference Facility': CampsiteKind.DAY_USE,
      'Retreat Centre': CampsiteKind.DAY_USE,
      'Daily Fishing': CampsiteKind.DAY_USE,
      'Guided Event': CampsiteKind.DAY_USE,
      'Hiking Trip': CampsiteKind.DAY_USE,
      Ferry: CampsiteKind.DAY_USE,
    };
    expect(classify(expected, campsiteKindAspira)).toEqual(expected);
  });

  test('Backcountry Cabin stays a cabin because exact names are checked before prefixes', () => {
    expect(campsiteKindAspira('Backcountry Cabin')).toBe(CampsiteKind.CABIN);
    expect(campsiteKindAspira('Backcountry Cabins')).toBe(CampsiteKind.BACKCOUNTRY);
  });

  test('names outside the table fall back to other', () => {
    const unclassifiable = [null, undefined, '', '   ', 'Boat Launch', 'campsite', 'constructor', 'toString'];
    expect(unclassifiable.map(campsiteKindAspira)).toEqual(unclassifiable.map(() => CampsiteKind.OTHER));
  });

  test('surrounding whitespace is trimmed before the lookup', () => {
    expect(campsiteKindAspira(' Backcountry Cabin ')).toBe(CampsiteKind.CABIN);
  });
});

describe('reserve california', () => {
  test('unit types map by substring in rule order', () => {
    const expected: Cases = {
      Campsite: CampsiteKind.STANDARD,
      'Tent Campsite': CampsiteKind.TENT,
      'Premium Campsite': CampsiteKind.STANDARD,
      'Hook Up (E/W/S) Campsite': CampsiteKind.RV,
      'Primitive Campsite': CampsiteKind.STANDARD,
      'Hike/Bike Campsite': CampsiteKind.WALK_IN,
      'Hike In Primitive Campsite': CampsiteKind.WALK_IN,
      'Group Campsite': CampsiteKind.GROUP,
      'Premium Cabin (8 People)': CampsiteKind.CABIN,
      'Bike In Campsite': CampsiteKind.WALK_IN,
      'Boat In Campsite': CampsiteKind.BOAT_IN,
      'Mini-Group Campsite': CampsiteKind.GROUP,
      'Group Day Use': CampsiteKind.DAY_USE,
      'Group Dailyuse (B)': CampsiteKind.DAY_USE,
      'Tent Only - Walk-In': CampsiteKind.WALK_IN,
      'Yurt (6 ppl)': CampsiteKind.CABIN,
      'Premium Cottage_3': CampsiteKind.CABIN,
      'Two Room Cabin': CampsiteKind.CABIN,
      'Floating Camp Campsite': CampsiteKind.BOAT_IN,
      'Equestrian Campsite': CampsiteKind.EQUESTRIAN,
      'Equestrain Group Tent Primitive Campsite': CampsiteKind.GROUP,
      'Equestrian Group Campsite': CampsiteKind.GROUP,
      'Premium Double Hook Up (E/W/S) Campsite': CampsiteKind.RV,
      'Group Hook Up (E ) Tent Campsite': CampsiteKind.GROUP,
      'Environmental Campsite': CampsiteKind.STANDARD,
      'ADA Campsite': CampsiteKind.STANDARD,
      'Hook Up Campsite': CampsiteKind.RV,
      'Group Camping': CampsiteKind.GROUP,
      'Equestrian Group Primitive Campsite': CampsiteKind.GROUP,
      'Tent Hike In Primitive Campsite': CampsiteKind.WALK_IN,
      'Premium Hook Up (E/W) Campsite': CampsiteKind.RV,
      'Tent Site': CampsiteKind.TENT,
      'Day Use': CampsiteKind.DAY_USE,
      site: CampsiteKind.OTHER,
      'RV Site': CampsiteKind.OTHER,
      '': CampsiteKind.OTHER,
    };
    expect(classify(expected, (input) => campsiteKindReserveCalifornia(input).kind)).toEqual(expected);
    expect(campsiteKindReserveCalifornia(null).kind).toBe(CampsiteKind.OTHER);
    expect(campsiteKindReserveCalifornia(undefined).kind).toBe(CampsiteKind.OTHER);
  });

  test('the vendor misspelling Equestrain Group Tent is a group site, not equestrian or tent', () => {
    expect(campsiteKindReserveCalifornia('Equestrain Group Tent Primitive Campsite').kind).toBe(CampsiteKind.GROUP);
    expect(campsiteKindReserveCalifornia('Equestrain Tent Campsite').kind).toBe(CampsiteKind.EQUESTRIAN);
  });

  test('Tent Only - Walk-In is a walk-in, not a tent site', () => {
    expect(campsiteKindReserveCalifornia('Tent Only - Walk-In').kind).toBe(CampsiteKind.WALK_IN);
  });

  test('rule order is the precedence: for every pair of rules the earlier one wins wherever the words sit', () => {
    const chain: Array<[word: string, kind: CampsiteKind]> = [
      ['day use', CampsiteKind.DAY_USE],
      ['group', CampsiteKind.GROUP],
      ['equestrian', CampsiteKind.EQUESTRIAN],
      ['cabin', CampsiteKind.CABIN],
      ['boat in', CampsiteKind.BOAT_IN],
      ['hike', CampsiteKind.WALK_IN],
      ['hook up', CampsiteKind.RV],
      ['tent', CampsiteKind.TENT],
      ['campsite', CampsiteKind.STANDARD],
    ];
    const outcomes: Record<string, CampsiteKind> = {};
    const expected: Record<string, CampsiteKind> = {};
    for (let earlier = 0; earlier < chain.length; earlier++) {
      for (let later = earlier + 1; later < chain.length; later++) {
        const [earlierWord, earlierKind] = chain[earlier]!;
        const [laterWord] = chain[later]!;
        for (const name of [`${laterWord} ${earlierWord}`, `${earlierWord} ${laterWord}`]) {
          outcomes[name] = campsiteKindReserveCalifornia(name).kind;
          expected[name] = earlierKind;
        }
      }
    }
    expect(outcomes).toEqual(expected);
  });

  test('electric hookups come off a hook up name carrying an (e marker', () => {
    expect(campsiteKindReserveCalifornia('Hook Up (E/W/S) Campsite').electric).toBe(true);
    expect(campsiteKindReserveCalifornia('Group Hook Up (E ) Tent Campsite').electric).toBe(true);
    expect(campsiteKindReserveCalifornia('Hook Up Campsite').electric).toBeNull();
    expect(campsiteKindReserveCalifornia('Tent Campsite').electric).toBeNull();
    expect(campsiteKindReserveCalifornia('Tent (E) Campsite').electric).toBeNull();
    expect(campsiteKindReserveCalifornia(null).electric).toBeNull();
  });
});
