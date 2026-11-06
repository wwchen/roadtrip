import { expect, test } from 'vitest';
import { stripTags } from './html-text.js';

const NBSP = ' ';
const BOM = '﻿';
const FILE_SEPARATOR = '\u001c';

test('strips tags and collapses whitespace', () => {
  expect(stripTags('<p>Walk-in <b>tent</b> site\n by the water.</p>')).toBe('Walk-in tent site by the water.');
});

test('leaves plain text untouched', () => {
  expect(stripTags('Quiet lakeside site')).toBe('Quiet lakeside site');
});

test('a tag spanning lines is removed whole', () => {
  expect(stripTags('a<span\n class="x">b</span>c')).toBe('a b c');
});

test('only ASCII whitespace collapses, as with the JVM regex class the Kotlin used', () => {
  expect(stripTags(`a${NBSP}${NBSP}b  c`)).toBe(`a${NBSP}${NBSP}b c`);
});

test('trim removes Unicode spaces but keeps a byte order mark, as Kotlin trim does', () => {
  expect(stripTags(`${NBSP} site ${NBSP}`)).toBe('site');
  expect(stripTags(`${BOM}site`)).toBe(`${BOM}site`);
  expect(stripTags(`${FILE_SEPARATOR}site${FILE_SEPARATOR}`)).toBe('site');
});

test('an empty or tag-only fragment yields the empty string', () => {
  expect(stripTags('')).toBe('');
  expect(stripTags('<br/><p></p>')).toBe('');
});
