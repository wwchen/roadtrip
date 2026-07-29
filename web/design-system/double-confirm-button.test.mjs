import assert from 'node:assert/strict';
import test from 'node:test';

import { doubleConfirmButtonTemplate } from './double-confirm-button-template.js';

// ── Pure template tests ──────────────────────────────────────────────────────

test('doubleConfirmButtonTemplate renders the label when disarmed', () => {
  const html = doubleConfirmButtonTemplate({ label: 'Sign out', armed: false });
  assert.match(html, /<button type="button"/);
  assert.match(html, />Sign out</);
  assert.doesNotMatch(html, /is-armed/);
});

test('doubleConfirmButtonTemplate swaps to confirmLabel and is-armed when armed', () => {
  const html = doubleConfirmButtonTemplate({
    label: 'Sign out',
    armed: true,
    confirmLabel: 'Confirm sign out',
  });
  assert.match(html, />Confirm sign out</);
  assert.match(html, /class="[^"]*is-armed/);
});

test('doubleConfirmButtonTemplate falls back to "Confirm?" with no confirmLabel', () => {
  const html = doubleConfirmButtonTemplate({ label: 'Delete', armed: true });
  assert.match(html, />Confirm\?</);
});

test('doubleConfirmButtonTemplate escapes the label', () => {
  const html = doubleConfirmButtonTemplate({ label: '<img src=x>', armed: false });
  assert.doesNotMatch(html, /<img/);
  assert.match(html, /&lt;img/);
});

// ── Size variant ─────────────────────────────────────────────────────────────
// Default is comfortable (>=44px) because this primitive fires destructive
// actions. Only dense fixed-width rows opt out.

test('doubleConfirmButtonTemplate omits the compact modifier by default', () => {
  const html = doubleConfirmButtonTemplate({ label: 'Sign out', armed: false });
  assert.match(html, /class="rt-dbl-btn"/);
  assert.doesNotMatch(html, /rt-dbl-btn--compact/);
});

test('doubleConfirmButtonTemplate adds the compact modifier for size "compact"', () => {
  const html = doubleConfirmButtonTemplate({
    label: '\u{1F5D1}',
    armed: false,
    size: 'compact',
  });
  assert.match(html, /rt-dbl-btn--compact/);
});

test('doubleConfirmButtonTemplate keeps compact and is-armed together', () => {
  const html = doubleConfirmButtonTemplate({
    label: '\u{1F5D1}',
    armed: true,
    confirmLabel: 'Delete?',
    size: 'compact',
  });
  assert.match(html, /rt-dbl-btn--compact/);
  assert.match(html, /is-armed/);
});

test('doubleConfirmButtonTemplate ignores an unknown size', () => {
  const html = doubleConfirmButtonTemplate({ label: 'X', armed: false, size: 'huge' });
  assert.match(html, /class="rt-dbl-btn"/);
});
