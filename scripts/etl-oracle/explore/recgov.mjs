const SCALARS = [
  'null', 'true', 'false', '0', '1', '7', '-3', '25.0', '1.10', '1e1', '10e-1', '100000000000000000000', '2147483648',
  '""', '" "', '"7"', '" 7 "', '"+7"', '"true"', '"TRUE"', '"yes"', '"\\u00a0"', '"x"', '"X y"', '"\\u0664"', '"7 ft"',
  '"1e1"', '"99999999999"', '"null"', '"Image"', '"IMAGE"', '"Official Web Site"', '"official web site"',
  '"https://x/y"', '" https://x/y "', '"USA"', '"United States"', '"can"', '"de"', '"\\u00df"', '"ABC & DEF  ghi"', '"A\\tB"',
];
const NON_SCALARS = ['[]', '{}', '[1]', '{"a":1}'];
const NON_SCALAR_RATE = 0.04;
const J = JSON.stringify;

const AGENCIES = [
  { agency: { kind: 'derived', field: 'ORGANIZATION[0].OrgName' } },
  { agency: null },
  { agency: { kind: 'constant', value: 'Fixed' } },
];
const CAMPSITE_ATTRIBUTE_NAMES = ['Fire Pit', 'FIRE PIT', 'Picnic Table', 'Accessible', 'ADA Accessible', 'Max Num of Vehicles', 'Max Vehicles', 'Driveway Length', 'Max Vehicle Length', 'Shade', '  Fire Pit ', '', ' '];
const CAMPSITE_ATTRIBUTE_VALUES = ['"Yes"', '"y"', '"NO"', '"n"', '"true"', '"False"', '" yes "', '""', 'null', '"27 ft"', '"40"', '"007"', '"\\u0663"', '"99999999999"', '"+5"', '"-5"', '" 12"', '5', '1.10', 'true', '{}', '[]', '"Seasonal"', '"2147483647"', '"2147483648"'];

function random(seed) {
  let state = seed >>> 0;
  return () => {
    state = (Math.imul(state, 1664525) + 1013904223) >>> 0;
    return state / 4294967296;
  };
}

function toolkit(seed) {
  const next = random(seed);
  const pick = (items) => items[Math.floor(next() * items.length)];
  const maybe = (rate, make) => (next() < rate ? make() : undefined);
  const scalar = () => (next() < NON_SCALAR_RATE ? pick([...SCALARS, ...NON_SCALARS]) : pick(SCALARS));
  const list = (maxLength, make) => Array.from({ length: Math.floor(next() * (maxLength + 1)) }, make);
  return { next, pick, maybe, scalar, list };
}

function facility(t, id) {
  const fields = [];
  const add = (key, value) => {
    if (value !== undefined) fields.push(`${J(key)}:${value}`);
  };
  add('FacilityID', t.pick([`"${id}"`, String(id), `"0${id}"`]));
  add('FacilityName', t.pick(['"Camp"', '" Camp "', 'null', '" "', '5', '"X"']));
  add('FacilityLatitude', t.pick(['10', '"38.5"', '0', 'null', '25.0', '"NaN"', '1e400', '12.345']));
  add('FacilityLongitude', t.pick(['20', '-119.2', '0', 'null', '"abc"', '0.0']));
  add('FacilityPhone', t.maybe(0.5, () => t.pick(['"555"', '" "', 'null', '""'])));
  add('FacilityReservationURL', t.maybe(0.5, () => t.pick(['"https://e/book"', '""', '" "', 'null', '" x "'])));
  add('FacilityDescription', t.maybe(0.5, t.scalar));
  add('Reservable', t.maybe(0.8, t.scalar));
  add('ACTIVITY', t.maybe(0.6, () => `[${t.list(3, () => `{"ActivityName":${t.scalar()}}`).join(',')}]`));
  add('MEDIA', t.maybe(0.6, () => `[${t.list(3, () => `{"URL":${t.pick(['"u1"', '"u2"', '" "', 'null'])},"IsPrimary":${t.scalar()},"IsPreview":${t.scalar()},"MediaType":${t.scalar()}}`).join(',')}]`));
  add('LINK', t.maybe(0.6, () => `[${t.list(3, () => `{"URL":${t.pick(['"l1"', '"l2"', '" "', 'null'])},"LinkType":${t.scalar()}}`).join(',')}]`));
  add('ORGANIZATION', t.maybe(0.8, () => t.pick([`[{"OrgName":${t.scalar()}}]`, '[]', 'null', '{"OrgName":"X"}', '[{"OrgName":"A"},{"OrgName":"B"}]', '[null]'])));
  add('RECAREA', t.maybe(0.6, () => t.pick([`[{"RecAreaName":${t.scalar()}}]`, '[]', 'null', '["x"]', '[{"RecAreaName":" Y "}]'])));
  add(
    'FACILITYADDRESS',
    t.maybe(0.7, () => {
      const address = () =>
        `{"AddressStateCode":${t.pick(['"UT"', '" "', 'null', '""'])},"AddressCountryCode":${t.pick(['"USA"', '"United States"', '"can"', '"de"', 'null', '"\\u00df"', '" us "'])},"City":${t.pick(['"Price"', '" "', 'null'])},"FacilityStreetAddress1":${t.pick(['"1 Main"', '""', 'null'])},"PostalCode":${t.pick(['"84501"', 'null'])}}`;
      return `[${t.list(2, address).join(',')}]`;
    }),
  );
  return `{${fields.join(',')}}`;
}

function enrichment(t, id) {
  const cells = `[${t.list(3, () => `{"carrier":${t.pick(['"Verizon"', '"at&t"', '"Sprint"', '"T-Mobile"', '"US Cellular"', '"Boost"', 'null', '5'])},"average_rating":${t.pick(['0', '3.5', '0.125', '"2.5"', 'null', '"x"', '4.336283185840708'])},"number_of_ratings":${t.pick(['3', '0', '-1', '3.0', '"3"', '1e1', 'null', '" 3"', '2147483648'])}}`).join(',')}]`;
  const location = t.maybe(0.3, () => `,"location_id":"${id}"`) ?? '';
  const aggregate = `{"average_rating":${t.pick(['4.5', '0.125', '0.625', '"4"', 'null', '5'])},"number_of_ratings":${t.pick(['8', '0', '"8"', '8.0', 'null', 'true'])},"aggregate_cell_coverage_ratings":${cells}${location}}`;
  const facilityId = t.pick([`"${id}"`, String(id), '"x"', 'null', `"+${id}"`, `"${id}.0"`]);
  return t.pick([`{"facility_id":${facilityId},"aggregate":${aggregate}}`, `{"aggregate":${aggregate}}`, aggregate.replace('{', `{"facility_id":"${id}",`)]);
}

function campgroundCase(t, n) {
  const ids = Array.from({ length: 1 + Math.floor(t.next() * 3) }, (_, i) => (t.next() < 0.2 ? 100 + i : 100 + i + n * 3));
  const page = { url: 'u', part: null, payload: `{"RECDATA":[${ids.map((id) => facility(t, id)).join(',')}]}` };
  const inputs = { 'recgov-campgrounds-raw': [page] };
  if (t.next() < 0.7) {
    inputs['recgov-campground-enrichment'] = t.list(2, () => ({ url: 'u', part: null, payload: enrichment(t, 100 + Math.floor(t.next() * 3) + n * 3) })).concat([{ url: 'u', part: null, payload: enrichment(t, ids[0]) }]);
  }
  return {
    adapter: 'recgov-campgrounds',
    ctor: ['recgov-campgrounds'],
    registry: { subcategory: 'federal', ...t.pick(AGENCIES), args: {}, inputs: Object.keys(inputs) },
    inputs,
  };
}

function campsite(t, id) {
  const fields = [];
  const add = (key, value) => {
    if (value !== undefined) fields.push(`${J(key)}:${value}`);
  };
  add('site', t.maybe(0.8, t.scalar));
  add('loop', t.maybe(0.6, t.scalar));
  add('campsite_type', t.maybe(0.7, () => t.pick(['"STANDARD NONELECTRIC"', '"RV ELECTRIC"', '" TENT ONLY NONELECTRIC "', '"x"', 'null', '5', '""', '"GROUP"'])));
  add('max_num_people', t.maybe(0.8, t.scalar));
  add('min_num_people', t.maybe(0.8, t.scalar));
  add('campsite_reserve_type', t.maybe(0.5, t.scalar));
  add('type_of_use', t.maybe(0.5, t.scalar));
  add('equipment_types', t.maybe(0.5, () => `[${t.list(3, t.scalar).join(',')}]`));
  add(
    'attributes',
    t.maybe(0.9, () => {
      if (t.next() < 0.25) return t.pick(['null', '{}', '7']);
      const entry = () =>
        t.pick([
          `{"attribute_name":${J(t.pick(CAMPSITE_ATTRIBUTE_NAMES))},"attribute_value":${t.pick(CAMPSITE_ATTRIBUTE_VALUES)}}`,
          `{"attribute_name":${J(t.pick(CAMPSITE_ATTRIBUTE_NAMES))},"attribute_value":${t.pick(CAMPSITE_ATTRIBUTE_VALUES)}}`,
          `{"attribute_name":${J(t.pick(CAMPSITE_ATTRIBUTE_NAMES))},"attribute_value":${t.pick(CAMPSITE_ATTRIBUTE_VALUES)}}`,
          'null',
          '5',
          '{"attribute_value":"x"}',
        ]);
      return `[${t.list(4, entry).join(',')}]`;
    }),
  );
  return `${J(String(id))}:${t.pick([`{${fields.join(',')}}`, `{${fields.join(',')}}`, `{${fields.join(',')}}`, 'null', '"x"'])}`;
}

function campsiteCase(t, n) {
  const sites = Array.from({ length: 1 + Math.floor(t.next() * 3) }, (_, i) => campsite(t, 1000 + i + n * 3));
  const body = `{"campsites":{${sites.join(',')}}}`;
  const payload = t.pick([body, body, body, '{}', '[]', '{"campsites":[]}']);
  const url = t.pick(['https://x/campground/232447/month?s=1', 'https://x/campground/55', 'https://x/nothing', 'https://x/campground//m']);
  const inputs = { 'recgov-campsites-raw': [{ url, part: t.pick([null, null, 'part-1']), payload }] };
  return {
    adapter: 'recgov-campsites',
    ctor: ['recgov-campsites'],
    registry: { subcategory: null, agency: null, args: {}, inputs: Object.keys(inputs) },
    inputs,
  };
}

export function generate({ seed, count }) {
  const t = toolkit(seed);
  const cases = [];
  for (let n = 0; n < count; n += 1) cases.push(campgroundCase(t, n));
  for (let n = 0; n < count; n += 1) cases.push(campsiteCase(t, n));
  return cases;
}
