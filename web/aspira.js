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

function localYmd(date) {
  const y = date.getFullYear();
  const m = String(date.getMonth() + 1).padStart(2, '0');
  const d = String(date.getDate()).padStart(2, '0');
  return `${y}-${m}-${d}`;
}

function localTimestampMillis(date) {
  const ymd = localYmd(date);
  const h = String(date.getHours()).padStart(2, '0');
  const m = String(date.getMinutes()).padStart(2, '0');
  const s = String(date.getSeconds()).padStart(2, '0');
  return `${ymd}T${h}:${m}:${s}.000`;
}

export function buildAspiraDeeplink({
  host,
  transactionLocationId,
  mapId,
  resourceLocationId,
  startDate,
  endDate,
}) {
  const today = new Date();
  const tomorrow = new Date(today);
  tomorrow.setDate(tomorrow.getDate() + 1);
  // Aspira's `searchTime` is naive ISO without trailing Z (local-shaped).
  // Servers don't validate the shape; matching the working URL just keeps
  // the wire bytes recognizable.
  const searchTime = localTimestampMillis(today);
  // WA's results-page redirect logic refuses to render unless flexibleSearch
  // carries a real anchor date; null breaks it. Use today, format YYYY-MM-DD.
  const flexAnchor = localYmd(today);

  const fields = {
    transactionLocationId: String(transactionLocationId),
    mapId: String(mapId),
    searchTabGroupId: '0',
    bookingCategoryId: '0',
    startDate: startDate || localYmd(today),
    endDate: endDate || localYmd(tomorrow),
    nights: '1',
    isReserving: 'true',
    equipmentId: '-32768',                // Aspira sentinel for "any equipment"
    subEquipmentId: '-32768',             // "any sub-equipment"
    peopleCapacityCategoryCounts: '[[-32767,null,1,null]]',
    searchTime,
    flexibleSearch: `[false,false,"${flexAnchor}",1]`,
    view: 'list',
    filterData: '{"-32756":"[[1],0,0,0]"}',
  };
  // Only include resourceLocationId when we actually have it. Sending the
  // string "NULL" (or omitting when required) makes WA bounce the user back
  // to the homepage instead of the results page.
  if (resourceLocationId != null) {
    fields.resourceLocationId = String(resourceLocationId);
  }
  const params = new URLSearchParams(fields);
  return `https://${host}/create-booking/results?${params}`;
}
