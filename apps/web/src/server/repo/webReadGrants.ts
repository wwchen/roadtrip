export const WEB_READ_ROLE = 'roadtrip_web';

export const AVAILABILITY_WRITER_ROLE = 'roadtrip_availability_writer';

export const WEB_READ_TABLES: readonly string[] = [
  'pois',
  'campgrounds',
  'poi_campgrounds',
  'campsites',
  'tesla_superchargers',
  'poi_tesla_superchargers',
  'planet_fitness_locations',
  'poi_planet_fitness_locations',
  'availability',
];

export function webReadGrantSql(role: string = WEB_READ_ROLE): string {
  return `GRANT SELECT ON ${WEB_READ_TABLES.join(', ')} TO ${role};`;
}
