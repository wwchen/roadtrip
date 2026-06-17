const STATUS_META = Object.freeze({
  available: Object.freeze({
    value: 'available',
    kind: 'available',
    label: 'A',
    aria: 'available',
    text: 'Available',
    heatmapClass: 'cell-available',
    detailClass: 'cg-status-ok',
  }),
  first_come: Object.freeze({
    value: 'first_come',
    kind: 'first-come',
    label: 'FF',
    aria: 'first come first served',
    text: 'First come first served',
    heatmapClass: 'cell-first-come',
    detailClass: 'cg-status-first-come',
  }),
  reserved: Object.freeze({
    value: 'reserved',
    kind: 'reserved',
    label: 'R',
    aria: 'reserved',
    text: 'Reserved',
    heatmapClass: 'cell-reserved',
    detailClass: 'cg-status-muted',
  }),
  closed: Object.freeze({
    value: 'closed',
    kind: 'closed',
    label: 'C',
    aria: 'closed',
    text: 'Closed',
    heatmapClass: 'cell-closed',
    detailClass: 'cg-status-muted',
  }),
  unknown: Object.freeze({
    value: 'unknown',
    kind: 'unknown',
    label: '?',
    aria: 'unknown',
    text: 'Unknown',
    heatmapClass: 'cell-unknown',
    detailClass: 'cg-status-unknown',
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

export function availabilityStatusHeatmapClass(raw) {
  return availabilityStatusMeta(raw).heatmapClass;
}
