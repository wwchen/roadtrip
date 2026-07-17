const STATUS_META = Object.freeze({
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
});

export function normalizeAvailabilityStatus(raw) {
  const value = String(raw || '').toLowerCase();
  return Object.prototype.hasOwnProperty.call(STATUS_META, value) ? value : 'unknown';
}

export function availabilityStatusMeta(raw) {
  return STATUS_META[normalizeAvailabilityStatus(raw)];
}

export function availabilityStatusLabel(raw) {
  return availabilityStatusMeta(raw).label;
}

export function availabilityStatusAria(raw) {
  return availabilityStatusMeta(raw).aria;
}
