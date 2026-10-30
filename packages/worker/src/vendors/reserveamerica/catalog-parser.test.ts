import { readFileSync } from 'node:fs';
import { describe, expect, test } from 'vitest';
import { parseCatalog, siteRows } from './catalog-parser.js';

const SMALLEST_CAPTURE = decodeURIComponent(
  new URL(
    '../../../../../data/raw/reserveamerica-campsites-ny/2026-07-05T20-28-53Z/campsite-3485-0.json',
    import.meta.url,
  ).pathname,
);
const SMALLEST_CAPTURE_ROW_COUNT = 1;
const UNIT_SEPARATOR = String.fromCodePoint(0x1f);
const FILE_SEPARATOR = String.fromCodePoint(0x1c);
const BYTE_ORDER_MARK = String.fromCodePoint(0xfeff);
const ARABIC_INDIC_THREE = String.fromCodePoint(0x663);

function row(label: string, siteId = '1', parkId = '2'): string {
  return `<div class='siteListLabel'><a href="/x/r/campsiteDetails.do?contractCode=NY&amp;siteId=${siteId}&amp;parkId=${parkId}">${label}</a></div>`;
}

describe('parseCatalog, ported from the Kotlin ReserveAmericaCatalogParserTest', () => {
  test('parses the site roster from campsite calendar rows', () => {
    const html = [
      "<span id='resulttotal_dr_top'>67</span>",
      `<div class='siteListLabel'><a href="/camping/woodland-valley/r/campsiteDetails.do?contractCode=NY&amp;siteId=253478&amp;parkId=489" aria-label='Site: 039 (253478)'>039</a></div>`,
      `<div class='siteListLabel'><a href="/camping/woodland-valley/r/campsiteDetails.do?contractCode=NY&amp;siteId=253497&amp;parkId=489" aria-label='Site: 056 (253497)'>056</a></div>`,
    ].join('\n');

    expect(parseCatalog(html)).toEqual([
      { parkId: '489', siteId: '253478', name: '039' },
      { parkId: '489', siteId: '253497', name: '056' },
    ]);
  });

  test('skips rows without a siteId', () => {
    expect(parseCatalog("<div class='siteListLabel'><a href='/camping/x'>bogus</a></div>")).toEqual([]);
  });
});

describe('the smallest real capture page', () => {
  test('yields the one roster row it carries, with the park and the label as the page spells them', () => {
    const envelope = JSON.parse(readFileSync(SMALLEST_CAPTURE, 'utf8')) as { payload: string };

    expect(siteRows(envelope.payload)).toHaveLength(SMALLEST_CAPTURE_ROW_COUNT);
    expect(parseCatalog(envelope.payload)).toEqual([{ parkId: '3485', siteId: '266466', name: 'Cottage' }]);
  });
});

describe('siteRows', () => {
  test('cuts at each marker, drops the text before the first, and runs the last row to the end of the page', () => {
    const html = `before${row('a')}middle${row('b')}after`;

    expect(siteRows(html)).toEqual([`${row('a')}middle`, `${row('b')}after`]);
  });

  test('is empty when the marker is absent, including when another attribute precedes the class', () => {
    expect(siteRows('<div class="siteListLabel">')).toEqual([]);
    expect(siteRows("<div id='d1' class='siteListLabel'>")).toEqual([]);
    expect(siteRows('')).toEqual([]);
  });
});

describe('a row', () => {
  test('takes the first siteId and the first parkId in the row', () => {
    const html = `<div class='siteListLabel'><a href="x?siteId=7&amp;parkId=8&amp;siteId=9&amp;parkId=10">first</a><a>second</a>`;

    expect(parseCatalog(html)).toEqual([{ parkId: '8', siteId: '7', name: 'first' }]);
  });

  test('is skipped without a parkId, and ids read only ASCII digits', () => {
    expect(parseCatalog(`<div class='siteListLabel'><a href="x?siteId=7">a</a>`)).toEqual([]);
    expect(parseCatalog(`<div class='siteListLabel'><a href="x?siteId=${ARABIC_INDIC_THREE}&amp;parkId=8">a</a>`)).toEqual([]);
    expect(parseCatalog(`<div class='siteListLabel'><a href="x?siteId=7&amp;parkId=${ARABIC_INDIC_THREE}">a</a>`)).toEqual(
      [],
    );
  });

  test('names itself by its siteId when the label is absent, empty or only trimmable characters', () => {
    const noLabel = `<div class='siteListLabel'><a href="x?siteId=7&amp;parkId=8">`;
    const emptyLabel = row('', '7', '8');
    const separators = row(`${UNIT_SEPARATOR}${FILE_SEPARATOR}`, '7', '8');
    const spaces = row('  \t ', '7', '8');

    expect(parseCatalog(noLabel)[0]?.name).toBe('7');
    expect(parseCatalog(emptyLabel)[0]?.name).toBe('7');
    expect(parseCatalog(separators)[0]?.name).toBe('7');
    expect(parseCatalog(spaces)[0]?.name).toBe('7');
  });

  test('trims the label by Kotlin whitespace: the information separators go, the byte order mark stays', () => {
    expect(parseCatalog(row(`${UNIT_SEPARATOR}  039${FILE_SEPARATOR}`))[0]?.name).toBe('039');
    expect(parseCatalog(row(`${BYTE_ORDER_MARK}039`))[0]?.name).toBe(`${BYTE_ORDER_MARK}039`);
  });

  test('finds no label when markup sits inside the anchor, because the text must run straight into the closing tag', () => {
    expect(parseCatalog(row('<b>bold</b>'))[0]?.name).toBe('1');
    expect(parseCatalog(row('text <b>bold</b>'))[0]?.name).toBe('1');
    expect(parseCatalog(`<div class='siteListLabel'><a href="x?siteId=5&amp;parkId=6">one</a><b>x</b></a>`)[0]?.name).toBe(
      'one',
    );
  });
});
