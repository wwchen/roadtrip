export interface ParsedPark {
  parkId: bigint;
  name: string;
  lat: number;
  lon: number;
  phone: string | null;
  description: string | null;
  photoUrl: string | null;
  infoUrl: string | null;
}
