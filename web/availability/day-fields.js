import { normalizeAvailabilityStatus } from '../utils/availability-status.js';

export function reservableStatuses(day) {
  const statuses = day?.reservable_statuses ?? day?.reservableStatuses;
  return statuses && typeof statuses === 'object' && !Array.isArray(statuses) ? statuses : {};
}

export function availableReservableIds(day) {
  const ids = day?.available_reservable_ids ?? day?.availableReservableIds;
  if (Array.isArray(ids)) return ids.map(String);
  return Object.entries(reservableStatuses(day))
    .filter(([, status]) => normalizeAvailabilityStatus(status) === 'available')
    .map(([rid]) => String(rid));
}

export function availableCount(day) {
  return availableReservableIds(day).length;
}

export function reservableCount(day) {
  return Object.keys(reservableStatuses(day)).length;
}
