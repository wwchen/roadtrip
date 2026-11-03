import { describe, expect, test } from 'vitest';
import type { JsonObject } from '../../etl/json-decode.js';
import { campflareContact, campflareLinks, campflareLocation, campflareManagement, campflarePhotos } from './fields.js';
import { CAMPFLARE_SOURCE_LINK_TITLE } from './support.js';

function obj(text: string): JsonObject {
  return JSON.parse(text) as JsonObject;
}

describe('campflareLocation', () => {
  test('keeps elevation and normalizes the nested address keys', () => {
    const raw = obj(`{"latitude":37.7,"longitude":-119.5,"elevation":4000,"directions":"Past the gate.",
      "address":{"street1":"1 Park Rd","state_code":"CA","zipcode":"95389","country_code":"US","full":"1 Park Rd, CA 95389"}}`);
    expect(campflareLocation(raw, 37.7, -119.5)).toEqual({
      latitude: 37.7,
      longitude: -119.5,
      elevation: 4000,
      directions: 'Past the gate.',
      address: { street: '1 Park Rd', state: 'CA', postcode: '95389', country: 'US', full: '1 Park Rd, CA 95389' },
    });
  });

  test('an address with no usable key carries none', () => {
    expect(campflareLocation(obj('{"latitude":1,"longitude":2,"address":{"note":"unusable"}}'), 1, 2)).toEqual({
      latitude: 1,
      longitude: 2,
    });
  });

  test('address keys are read in precedence order and a blank first key falls through', () => {
    const raw = obj('{"address":{"street":"  ","street1":"B","address_line":"C","state":"X","state_code":"Y","city":"Town"}}');
    expect(campflareLocation(raw, 1, 2).address).toEqual({ street: 'B', state: 'X', city: 'Town' });
  });
});

describe('campflarePhotos', () => {
  test('prefer url then large medium small original and drop entries without one', () => {
    const raw = obj('{"photos":[{"original_url":"o","large_url":"l"},{"small_url":"s"},{"caption":"none"},"x"]}');
    expect(campflarePhotos(raw.photos as unknown[])).toEqual([{ url: 'l' }, { url: 's' }]);
    expect(campflarePhotos(null)).toEqual([]);
  });
});

describe('campflareLinks', () => {
  test('keep title and append the Campflare source link once', () => {
    const raw = obj('{"links":[{"href":"https://nps.test","label":"NPS"},{"url":"https://campflare.test/c/1"}]}');
    expect(campflareLinks(raw.links as unknown[], 'https://campflare.test/c/1')).toEqual([
      { url: 'https://nps.test', title: 'NPS' },
      { url: 'https://campflare.test/c/1' },
    ]);
    expect(campflareLinks(null, 'https://campflare.test/c/2')).toEqual([
      { url: 'https://campflare.test/c/2', title: CAMPFLARE_SOURCE_LINK_TITLE },
    ]);
  });

  test('the title is the first of title, label and name that is present', () => {
    const raw = obj(`{"links":[
      {"url":"https://a.test","name":"N","label":"L","title":"T"},
      {"url":"https://b.test","name":"N","label":"L"},
      {"url":"https://c.test","name":"N"},
      {"url":"https://d.test"},
      {"title":"orphan"}
    ]}`);
    expect(campflareLinks(raw.links as unknown[], 'https://s.test').map((link) => link.title)).toEqual([
      'T',
      'L',
      'N',
      undefined,
      CAMPFLARE_SOURCE_LINK_TITLE,
    ]);
  });

  test('an upstream link to the source page suppresses the appended one only on an exact match', () => {
    const raw = obj('{"links":[{"url":"https://campflare.test/c/1/"}]}');
    expect(campflareLinks(raw.links as unknown[], 'https://campflare.test/c/1')).toEqual([
      { url: 'https://campflare.test/c/1/' },
      { url: 'https://campflare.test/c/1', title: CAMPFLARE_SOURCE_LINK_TITLE },
    ]);
  });
});

describe('campflareManagement', () => {
  test('needs an agency and carries the website', () => {
    expect(
      campflareManagement(obj('{"agency_name":"National Park Service","agency_id":7,"agency_website":"https://nps.test"}')),
    ).toEqual({ agency: 'National Park Service', website: 'https://nps.test' });
    expect(campflareManagement(obj('{"agency_id":7}'))).toBeNull();
    expect(campflareManagement(null)).toBeNull();
  });
});

describe('campflareContact', () => {
  test('maps primary keys and is null when empty', () => {
    expect(campflareContact(obj('{"primary_phone":"555","primary_email":"a@b.test"}'))).toEqual({
      phone: '555',
      email: 'a@b.test',
    });
    expect(campflareContact(obj('{"fax":"1"}'))).toBeNull();
    expect(campflareContact(obj('{"email":"a@b.test"}'))).toEqual({ email: 'a@b.test' });
  });
});
