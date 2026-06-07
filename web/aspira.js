// Builder for Aspira NextGen booking deeplinks.
//
// Aspira powers reservation.pc.gc.ca (Parks Canada), washington.goingtocamp.com
// (Washington State Parks), discovercamping.ca (BC Parks), and several others
// — they all share the same /create-booking/results URL shape. Per-park IDs
// are sourced from the public /api/maps endpoint and stamped into the curated
// data files (see scripts/fetch_parks_canada_aspira.py and RFC 0006).
//
// The querystring carries a lot of inert defaults; only `transactionLocationId`,
// `mapId`, and the dates are per-trip. Dates default to today/tomorrow so the
// user lands on a usable booking page they can adjust.

export function buildAspiraDeeplink({
  host,
  transactionLocationId,
  mapId,
  resourceLocationId = 'NULL',
  startDate,
  endDate,
}) {
  const today = new Date();
  const tomorrow = new Date(today.getTime() + 86_400_000);
  const ymd = (d) => d.toISOString().slice(0, 10);
  // Aspira's `searchTime` is naive ISO without trailing Z (local-shaped).
  // Servers don't validate the shape; matching the working URL just keeps
  // the wire bytes recognizable.
  const searchTime = today.toISOString().replace(/\.\d+Z$/, '.000');

  const params = new URLSearchParams({
    transactionLocationId: String(transactionLocationId),
    resourceLocationId: String(resourceLocationId),
    mapId: String(mapId),
    searchTabGroupId: '0',
    bookingCategoryId: '0',
    startDate: startDate || ymd(today),
    endDate: endDate || ymd(tomorrow),
    nights: '1',
    isReserving: 'true',
    equipmentId: '-32768',                // Aspira sentinel for "any equipment"
    subEquipmentId: '-32768',             // "any sub-equipment"
    peopleCapacityCategoryCounts: '[[-32767,null,1,null]]',
    searchTime,
    flexibleSearch: '[false,false,null,1]',
    view: 'list',
    filterData: '{"-32756":"[[1],0,0,0]"}',
  });
  return `https://${host}/create-booking/results?${params}`;
}
