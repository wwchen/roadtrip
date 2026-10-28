const POINT_TYPE = 'Point';

export function pointGeoJson(lng: number, lat: number): string {
  return JSON.stringify({ type: POINT_TYPE, coordinates: [lng, lat] });
}
