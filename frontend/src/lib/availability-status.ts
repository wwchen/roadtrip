// The union is the generated one, so a status added to the backend's
// `AvailabilityStatus` makes `STATUS_META` below a compile error until it is
// given a label and an aria string. This module is the single
// place that maps the wire values to display strings.

import type { AvailabilityStatus } from '@/api/generated/api-types';
import { entryOf } from '@/lib/entry-of';

export type { AvailabilityStatus };

export interface AvailabilityStatusMeta {
  value: AvailabilityStatus;
  /** CSS-friendly variant of `value` (underscores become hyphens). */
  kind: string;
  /** Single-cell glyph for the availability grid. */
  label: string;
  /** Spoken form, for the grid cell's accessible name. */
  aria: string;
  /** Sentence-case form, for detail views. */
  text: string;
}

const STATUS_META: Readonly<Record<AvailabilityStatus, Readonly<AvailabilityStatusMeta>>> =
  Object.freeze({
    available: Object.freeze({
      value: 'available',
      kind: 'available',
      label: 'A',
      aria: 'available',
      text: 'Available',
    }),
    first_come: Object.freeze({
      value: 'first_come',
      kind: 'first-come',
      label: 'FF',
      aria: 'first come first served',
      text: 'First come first served',
    }),
    reserved: Object.freeze({
      value: 'reserved',
      kind: 'reserved',
      label: 'R',
      aria: 'reserved',
      text: 'Reserved',
    }),
    closed: Object.freeze({
      value: 'closed',
      kind: 'closed',
      label: 'C',
      aria: 'closed',
      text: 'Closed',
    }),
    unknown: Object.freeze({
      value: 'unknown',
      kind: 'unknown',
      label: '?',
      aria: 'unknown',
      text: 'Unknown',
    }),
    past: Object.freeze({
      value: 'past',
      kind: 'past',
      label: '·',
      aria: 'past',
      text: 'Past',
    }),
  } satisfies Record<AvailabilityStatus, AvailabilityStatusMeta>);

/**
 * Coerce anything to a known status.
 *
 * Unrecognised values become `unknown` rather than throwing: a provider that
 * grows a new status must not blank out the whole availability grid.
 */
export function normalizeAvailabilityStatus(raw: unknown): AvailabilityStatus {
  const value = String(raw || '').toLowerCase();
  return entryOf(STATUS_META, value) ? (value as AvailabilityStatus) : 'unknown';
}

export function availabilityStatusMeta(raw: unknown): Readonly<AvailabilityStatusMeta> {
  return STATUS_META[normalizeAvailabilityStatus(raw)];
}

export function availabilityStatusLabel(raw: unknown): string {
  return availabilityStatusMeta(raw).label;
}

export function availabilityStatusAria(raw: unknown): string {
  return availabilityStatusMeta(raw).aria;
}
