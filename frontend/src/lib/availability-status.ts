// The six values are the backend's `AvailabilityStatus` wire values
// (backend/.../model/availability/AvailabilityStatus.kt). This module is the
// single place that maps them to display strings, so the API layer imports the
// type from here rather than restating the union.

export type AvailabilityStatus =
  | 'available'
  | 'first_come'
  | 'reserved'
  | 'closed'
  | 'unknown'
  | 'past';

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
  detailClass: string;
}

const STATUS_META: Readonly<Record<AvailabilityStatus, Readonly<AvailabilityStatusMeta>>> =
  Object.freeze({
    available: Object.freeze({
      value: 'available',
      kind: 'available',
      label: 'A',
      aria: 'available',
      text: 'Available',
      detailClass: 'cg-status-ok',
    }),
    first_come: Object.freeze({
      value: 'first_come',
      kind: 'first-come',
      label: 'FF',
      aria: 'first come first served',
      text: 'First come first served',
      detailClass: 'cg-status-first-come',
    }),
    reserved: Object.freeze({
      value: 'reserved',
      kind: 'reserved',
      label: 'R',
      aria: 'reserved',
      text: 'Reserved',
      detailClass: 'cg-status-muted',
    }),
    closed: Object.freeze({
      value: 'closed',
      kind: 'closed',
      label: 'C',
      aria: 'closed',
      text: 'Closed',
      detailClass: 'cg-status-muted',
    }),
    unknown: Object.freeze({
      value: 'unknown',
      kind: 'unknown',
      label: '?',
      aria: 'unknown',
      text: 'Unknown',
      detailClass: 'cg-status-unknown',
    }),
    past: Object.freeze({
      value: 'past',
      kind: 'past',
      label: '·',
      aria: 'past',
      text: 'Past',
      detailClass: 'cg-status-muted',
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
  return Object.prototype.hasOwnProperty.call(STATUS_META, value)
    ? (value as AvailabilityStatus)
    : 'unknown';
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
