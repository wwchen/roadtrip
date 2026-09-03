import { beforeEach, expect, test } from 'vitest';
import { resolveSprite } from '@lew-ds/lds-react';
import spriteMarkup from '@lew-ds/open-icons/icons.svg?raw';
import { installIconSprite } from './icon-sprite';

/** Every `mask`/`clipPath` id the sprite's own artwork refers to. */
const INTERNAL_REFERENCES = [...spriteMarkup.matchAll(/url\(#([^)]+)\)/g)].map((m) => m[1]);

beforeEach(() => {
  document.body.innerHTML = '';
});

test('the sprite carries symbols whose artwork refers to ids inside it', () => {
  // Guards the premise, not the fix: if a future export drew every symbol from
  // paths alone, this whole module would be dead weight and should be deleted
  // rather than left to rot. 54 of 174 symbols carry one today.
  expect(INTERNAL_REFERENCES.length).toBeGreaterThan(0);
});

test('every id the artwork refers to resolves in the same document', () => {
  installIconSprite();

  const dangling = INTERNAL_REFERENCES.filter((id) => document.getElementById(id) === null);

  expect(dangling).toEqual([]);
});

test('components reference the sprite in this document, not across files', () => {
  // The other half of the fix. Inlined ids do nothing while a glyph still points
  // at `.../icons.svg#name`: an external `<use>` resolves the mask against the
  // file it was cloned from, so the reference dangles however many copies of the
  // sprite this document holds.
  installIconSprite();

  expect(resolveSprite()).toBe('');
  expect(`${resolveSprite()}#warning-fill`).toBe('#warning-fill');
});

test('installing twice leaves one sprite', () => {
  installIconSprite();
  installIconSprite();

  expect(document.querySelectorAll('#lds-icon-sprite')).toHaveLength(1);
  expect(document.getElementById('warning-fill')).not.toBeNull();
});

test('the sprite is out of layout but never out of the render tree', () => {
  // The third half of the fix, and the quiet one. `display: none` anywhere above
  // a `<mask>` takes it out of the render tree, and a mask that is not rendered
  // does not apply — the group draws unmasked rather than vanishing, so every
  // masked glyph loses its moats while still looking like an icon.
  installIconSprite();

  const holder = document.getElementById('lds-icon-sprite');
  const sprite = holder?.querySelector('svg');

  expect(holder?.style.display).not.toBe('none');
  expect(holder?.hidden).toBe(false);
  expect(sprite?.style.display).toBe('block');
  expect(holder?.style.position).toBe('absolute');
  expect(holder?.style.overflow).toBe('hidden');
});

test('the sprite sits beside the page mount point, not inside it', () => {
  document.body.innerHTML = '<div id="root"></div>';

  installIconSprite();

  const holder = document.getElementById('lds-icon-sprite');
  expect(holder?.parentElement).toBe(document.body);
  expect(document.getElementById('root')?.contains(holder ?? null)).toBe(false);
  expect(holder?.getAttribute('aria-hidden')).toBe('true');
});
