import { normalizeAvailabilityStatus } from '../utils/availability-status.js';

export function campsiteStatuses(day) {
  const statuses = day?.campsite_statuses;
  return statuses && typeof statuses === 'object' && !Array.isArray(statuses) ? statuses : {};
}

export function availableCampsiteIds(day) {
  const ids = day?.available_campsite_ids;
  if (Array.isArray(ids)) return ids.map(String);
  return Object.entries(campsiteStatuses(day))
    .filter(([, status]) => normalizeAvailabilityStatus(status) === 'available')
    .map(([id]) => String(id));
}

export function availableCount(day) {
  return availableCampsiteIds(day).length;
}

export function campsiteCount(day) {
  return Object.keys(campsiteStatuses(day)).length;
}
