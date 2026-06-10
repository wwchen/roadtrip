export function requestRoute({ stops, radiusMiles, signal }) {
  const coords = stops.map(s => `${s.lng},${s.lat}`).join(';');
  const params = new URLSearchParams({
    coords,
    radius_miles: String(radiusMiles),
  });
  return fetch(`/api/route?${params.toString()}`, { signal });
}
